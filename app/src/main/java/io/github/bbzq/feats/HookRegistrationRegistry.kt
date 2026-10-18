package io.github.bbzq.feats

/** 仅在安装阶段使用；不在播放器或 HTTP 回调的热路径加锁/查表。 */
internal class HookRegistrationRegistry {
    private data class Key(val owner: Class<*>?, val target: Any, val kind: String, val site: Any, val callbackType: Class<*>)
    private val installed = HashSet<Key>()
    private val owner = ThreadLocal<Class<*>?>()

    fun <T> withOwner(type: Class<*>, block: () -> T): T {
        val previous = owner.get()
        owner.set(type)
        try { return block() } finally { owner.set(previous) }
    }

    @Synchronized
    fun install(target: Any, kind: String, callback: Any, site: Any, register: () -> Unit): Boolean {
        val key = Key(owner.get(), target, kind, site, callback.javaClass)
        if (key in installed) return true
        // 注册抛异常时不记成功，允许后续符号补全再试。
        register()
        installed += key
        return true
    }
}
