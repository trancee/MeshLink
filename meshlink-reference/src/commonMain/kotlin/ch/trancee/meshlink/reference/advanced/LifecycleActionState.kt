package ch.trancee.meshlink.reference.advanced

/**
 * Action availability for lifecycle controls on the advanced surface.
 */
public data class LifecycleActionState(
    public val startEnabled: Boolean,
    public val pauseEnabled: Boolean,
    public val resumeEnabled: Boolean,
    public val stopEnabled: Boolean,
) {
    public companion object {
        public fun from(meshStateLabel: String): LifecycleActionState {
            return when {
                meshStateLabel.contains("Uninitialized") || meshStateLabel.contains("Stopped") ->
                    LifecycleActionState(
                        startEnabled = true,
                        pauseEnabled = false,
                        resumeEnabled = false,
                        stopEnabled = false,
                    )

                meshStateLabel.contains("Running") ->
                    LifecycleActionState(
                        startEnabled = false,
                        pauseEnabled = true,
                        resumeEnabled = false,
                        stopEnabled = true,
                    )

                meshStateLabel.contains("Paused") ->
                    LifecycleActionState(
                        startEnabled = false,
                        pauseEnabled = false,
                        resumeEnabled = true,
                        stopEnabled = true,
                    )

                else ->
                    LifecycleActionState(
                        startEnabled = true,
                        pauseEnabled = true,
                        resumeEnabled = true,
                        stopEnabled = true,
                    )
            }
        }
    }
}
