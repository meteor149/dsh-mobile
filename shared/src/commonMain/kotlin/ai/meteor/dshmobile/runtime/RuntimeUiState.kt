package ai.meteor.dshmobile.runtime

enum class RuntimePhase {
    Unavailable,
    NotInstalled,
    Installing,
    Ready,
    Starting,
    Running,
    Stopping,
    Failed,
}

data class RuntimeUiState(
    val phase: RuntimePhase = RuntimePhase.Unavailable,
    val runtimeVersion: String = "未提供制品",
    val detail: String = "先运行 runtime 构建任务，再重新打包应用。",
    val progress: Float? = null,
    val webUrl: String? = null,
    val logTail: List<String> = emptyList(),
) {
    val isBusy: Boolean
        get() = phase in setOf(
            RuntimePhase.Installing,
            RuntimePhase.Starting,
            RuntimePhase.Stopping,
        )
}
