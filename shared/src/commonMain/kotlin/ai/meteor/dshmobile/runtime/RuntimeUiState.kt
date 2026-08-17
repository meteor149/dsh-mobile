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

enum class RuntimeMessageKind {
    ArtifactsUnavailable,
    RuntimeReady,
    RuntimeNotInstalled,
    Installing,
    VerifyingRootfs,
    ExtractingUbuntu,
    ExtractingEntries,
    InstallComplete,
    Starting,
    Running,
    Stopping,
    Stopped,
    Failed,
}

data class RuntimeMessage(
    val kind: RuntimeMessageKind,
    val count: Int? = null,
)

data class RuntimeUiState(
    val phase: RuntimePhase = RuntimePhase.Unavailable,
    val runtimeVersion: String = "",
    val detail: RuntimeMessage = RuntimeMessage(RuntimeMessageKind.ArtifactsUnavailable),
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
