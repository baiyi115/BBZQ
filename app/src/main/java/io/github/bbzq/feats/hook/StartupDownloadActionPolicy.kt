package io.github.bbzq.feats.hook

/** 保留宿主动作数组的运行时类型与顺序；只移除启动自动恢复动作。 */
internal object StartupDownloadActionPolicy {
    const val AUTO_RESUME_ACTION =
        "tv.danmaku.bili.services.videodownload.action.AutoStartAllTaskIfNeedAction"

    fun filter(actions: Array<*>, autoResumeClassName: String = AUTO_RESUME_ACTION): Array<*> {
        val retained = actions.filterNot { it?.javaClass?.name == autoResumeClassName }
        if (retained.size == actions.size) return actions
        val componentType = requireNotNull(actions.javaClass.componentType)
        val result = java.lang.reflect.Array.newInstance(componentType, retained.size)
        retained.forEachIndexed { index, action -> java.lang.reflect.Array.set(result, index, action) }
        return result as Array<*>
    }
}
