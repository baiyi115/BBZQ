package io.github.bbzq.feats

import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

class MethodHookParam internal constructor(
    private val chain: XposedInterface.Chain,
    val executable: Executable,
    val thisObject: Any?,
    val args: MutableList<Any?>,
) {
    private var returnEarly = false
    private var resultValue: Any? = null

    val isArgsModified: Boolean
        get() = (args as? HookArgsList)?.isModified ?: true

    val argsArray: Array<Any?>
        get() = (args as? HookArgsList)?.snapshot() ?: args.toTypedArray()

    var result: Any?
        get() = resultValue
        set(value) {
            resultValue = value
            returnEarly = true
        }

    internal fun setInitialResult(value: Any?) {
        resultValue = value
        returnEarly = false
    }

    internal fun shouldReturnEarly(): Boolean = returnEarly

    fun invokeOriginalMethod(): Any? =
        if (isArgsModified) chain.proceed(argsArray) else chain.proceed()
}

/**
 * 包装框架传入参数列表的轻量 MutableList。
 *
 * 绝大多数 hook 只是读取参数或检查条件，从不修改参数。
 * 只有发生真实修改（set/add/remove）时才克隆底层列表并置 isModified 为 true；
 * 未修改时直接 chain.proceed() 原样传递，完全避免每次拦截的 toTypedArray 分配。
 */
internal class HookArgsList(
    private val rawArgs: List<Any?>,
) : AbstractMutableList<Any?>() {
    private var backingList: ArrayList<Any?>? = null
    var isModified: Boolean = false
        private set

    override val size: Int
        get() = backingList?.size ?: rawArgs.size

    override fun get(index: Int): Any? =
        backingList?.get(index) ?: rawArgs[index]

    override fun set(index: Int, element: Any?): Any? {
        val list = ensureBackingList()
        isModified = true
        return list.set(index, element)
    }

    override fun add(index: Int, element: Any?) {
        val list = ensureBackingList()
        isModified = true
        list.add(index, element)
    }

    override fun removeAt(index: Int): Any? {
        val list = ensureBackingList()
        isModified = true
        return list.removeAt(index)
    }

    private fun ensureBackingList(): ArrayList<Any?> {
        val current = backingList
        if (current != null) return current
        val created = ArrayList<Any?>(rawArgs.size)
        created.addAll(rawArgs)
        backingList = created
        return created
    }

    fun snapshot(): Array<Any?> =
        backingList?.toTypedArray() ?: rawArgs.toTypedArray()
}

typealias Hooker = (MethodHookParam) -> Unit
typealias Replacer = (MethodHookParam) -> Any?

/**
 * 在 hook 注册时预先解析出返回值的强制转换器，彻底消除每次拦截执行时的反射查询。
 */
internal fun resolveResultCoercer(executable: Executable): (Any?) -> Any? {
    if (executable !is Method) return { it }
    val returnType = executable.returnType
    if (returnType == java.lang.Void.TYPE || returnType == Void::class.java) {
        return { null }
    }
    if (returnType.isPrimitive) {
        return when (returnType) {
            java.lang.Boolean.TYPE -> { value -> value ?: java.lang.Boolean.FALSE }
            java.lang.Integer.TYPE -> { value -> value ?: 0 }
            java.lang.Long.TYPE -> { value -> value ?: 0L }
            java.lang.Float.TYPE -> { value -> value ?: 0.0f }
            java.lang.Double.TYPE -> { value -> value ?: 0.0 }
            java.lang.Byte.TYPE -> { value -> value ?: 0.toByte() }
            java.lang.Short.TYPE -> { value -> value ?: 0.toShort() }
            java.lang.Character.TYPE -> { value -> value ?: '\u0000' }
            else -> { value -> value }
        }
    }
    return { it }
}

fun safeCoerceResult(executable: Executable, value: Any?): Any? {
    if (executable !is Method) return value
    val returnType = executable.returnType
    if (returnType == java.lang.Void.TYPE || returnType == Void::class.java) {
        return null
    }
    if (value == null && returnType.isPrimitive) {
        return when (returnType) {
            java.lang.Boolean.TYPE -> java.lang.Boolean.FALSE
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0.0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
    }
    return value
}

fun RoamingEnv.hookBefore(executable: Executable, hooker: Hooker): Boolean =
    runCatching {
        hookRegistrations.install(executable, "hookBefore", hooker, hookRegistrationSite()) {
            executable.isAccessible = true
            val coercer = resolveResultCoercer(executable)
            xposed.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val param = MethodHookParam(
                        chain = chain,
                        executable = executable,
                        thisObject = chain.getThisObject(),
                        args = HookArgsList(chain.getArgs()),
                    )
                    runCatching {
                        hooker(param)
                    }.onFailure { throwable ->
                        log("Hook before failed at ${executable.declaringClass.name}.${executable.name}", throwable)
                    }
                    if (param.shouldReturnEarly()) {
                        coercer(param.result)
                    } else if (param.isArgsModified) {
                        chain.proceed(param.argsArray)
                    } else {
                        chain.proceed()
                    }
                }
        }
    }.getOrElse { throwable ->
        log("Failed to register hookBefore at ${executable.declaringClass.name}.${executable.name}", throwable)
        false
    }

fun RoamingEnv.hookAfter(executable: Executable, hooker: Hooker) {
    runCatching {
        hookRegistrations.install(executable, "hookAfter", hooker, hookRegistrationSite()) {
            executable.isAccessible = true
            val coercer = resolveResultCoercer(executable)
            xposed.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val param = MethodHookParam(
                        chain = chain,
                        executable = executable,
                        thisObject = chain.getThisObject(),
                        args = HookArgsList(chain.getArgs()),
                    )
                    val initial = chain.proceed()
                    param.setInitialResult(initial)
                    runCatching {
                        hooker(param)
                    }.onFailure { throwable ->
                        log("Hook after failed at ${executable.declaringClass.name}.${executable.name}", throwable)
                    }
                    coercer(param.result)
                }
        }
    }.onFailure { throwable ->
        log("Failed to register hookAfter at ${executable.declaringClass.name}.${executable.name}", throwable)
    }
}

fun RoamingEnv.replace(executable: Executable, replacer: Replacer) {
    runCatching {
        hookRegistrations.install(executable, "replace", replacer, hookRegistrationSite()) {
            executable.isAccessible = true
            val coercer = resolveResultCoercer(executable)
            xposed.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val param = MethodHookParam(
                        chain = chain,
                        executable = executable,
                        thisObject = chain.getThisObject(),
                        args = HookArgsList(chain.getArgs()),
                    )
                    val computed = runCatching {
                        replacer(param)
                    }.getOrElse { throwable ->
                        log("Hook replace failed at ${executable.declaringClass.name}.${executable.name}, invoking original", throwable)
                        return@intercept if (param.isArgsModified) chain.proceed(param.argsArray) else chain.proceed()
                    }
                    coercer(computed)
                }
        }
    }.onFailure { throwable ->
        log("Failed to register replace at ${executable.declaringClass.name}.${executable.name}", throwable)
    }
}

fun RoamingEnv.intercept(executable: Executable, block: (XposedInterface.Chain) -> Any?) {
    runCatching {
        hookRegistrations.install(executable, "intercept", block, hookRegistrationSite()) {
            executable.isAccessible = true
            val coercer = resolveResultCoercer(executable)
            xposed.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val res = runCatching {
                        block(chain)
                    }.getOrElse { throwable ->
                        log("Hook intercept failed at ${executable.declaringClass.name}.${executable.name}", throwable)
                        chain.proceed()
                    }
                    coercer(res)
                }
        }
    }.onFailure { throwable ->
        log("Failed to register intercept at ${executable.declaringClass.name}.${executable.name}", throwable)
    }
}

fun RoamingEnv.hookBeforeMethod(
    type: Class<*>,
    methodName: String,
    vararg parameterTypes: Class<*>,
    hooker: Hooker,
): Int {
    val method = type.findMethodOrNull(methodName, *parameterTypes) ?: return 0
    hookBefore(method, hooker)
    return 1
}

fun RoamingEnv.hookAfterMethod(
    type: Class<*>,
    methodName: String,
    vararg parameterTypes: Class<*>,
    hooker: Hooker,
): Int {
    val method = type.findMethodOrNull(methodName, *parameterTypes) ?: return 0
    hookAfter(method, hooker)
    return 1
}

fun RoamingEnv.replaceMethod(
    type: Class<*>,
    methodName: String,
    vararg parameterTypes: Class<*>,
    replacer: Replacer,
): Int {
    val method = type.findMethodOrNull(methodName, *parameterTypes) ?: return 0
    replace(method, replacer)
    return 1
}

fun RoamingEnv.hookBeforeAllMethods(type: Class<*>, methodName: String?, hooker: Hooker): Int {
    val methods = runCatching {
        type.allMethods()
            .filter { methodName == null || it.name == methodName }
            .distinctBy(Method::toGenericString)
            .toList()
    }.getOrElse { emptyList() }
    methods.forEach { hookBefore(it, hooker) }
    return methods.size
}

fun RoamingEnv.hookAfterAllMethods(type: Class<*>, methodName: String?, hooker: Hooker): Int {
    val methods = runCatching {
        type.allMethods()
            .filter { methodName == null || it.name == methodName }
            .distinctBy(Method::toGenericString)
            .toList()
    }.getOrElse { emptyList() }
    methods.forEach { hookAfter(it, hooker) }
    return methods.size
}

fun RoamingEnv.hookBeforeAllConstructors(type: Class<*>, hooker: Hooker): Int {
    val constructors = runCatching {
        type.declaredConstructors.onEach { it.isAccessible = true }
    }.getOrElse { emptyArray() }
    constructors.forEach { hookBefore(it, hooker) }
    return constructors.size
}

fun RoamingEnv.hookAfterAllConstructors(type: Class<*>, hooker: Hooker): Int {
    val constructors = runCatching {
        type.declaredConstructors.onEach { it.isAccessible = true }
    }.getOrElse { emptyArray() }
    constructors.forEach { hookAfter(it, hooker) }
    return constructors.size
}

fun RoamingEnv.hookBeforeConstructor(
    type: Class<*>,
    vararg parameterTypes: Class<*>,
    hooker: Hooker,
): Int {
    val constructor = runCatching {
        type.getDeclaredConstructor(*parameterTypes).apply { isAccessible = true }
    }.getOrNull() ?: return 0
    hookBefore(constructor, hooker)
    return 1
}

private fun Class<*>.findMethodOrNull(name: String, vararg parameterTypes: Class<*>): Method? {
    return runCatching {
        getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
    }.getOrNull() ?: methodsNamed(name).firstOrNull {
        it.name == name && it.parameterTypes.contentEquals(parameterTypes)
    }
}


/** 安装期记录调用位置，避免 R8 合并 lambda 类后误把两个独立拦截器当成一个。 */
private fun hookRegistrationSite(): StackTraceElement =
    Throwable().stackTrace.let { trace ->
        val helperClass = trace.first().className
        trace.first { it.className != helperClass }
    }
