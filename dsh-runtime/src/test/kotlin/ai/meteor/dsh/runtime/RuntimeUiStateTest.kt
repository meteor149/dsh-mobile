package ai.meteor.dsh.runtime
import ai.meteor.ubuntu.runtime.*

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeUiStateTest {
    @Test
    fun transientPhasesAreBusy() {
        assertTrue(RuntimeUiState(phase = RuntimePhase.Installing).isBusy)
        assertTrue(RuntimeUiState(phase = RuntimePhase.Starting).isBusy)
        assertTrue(RuntimeUiState(phase = RuntimePhase.Stopping).isBusy)
        assertFalse(RuntimeUiState(phase = RuntimePhase.Ready).isBusy)
    }

    @Test
    fun prootIsTheSafeDefault() {
        val state = RuntimeUiState()

        assertEquals(RuntimeMode.Proot, state.runtimeMode)
        assertEquals(RootAccessState.NotRequired, state.rootAccess)
    }

    @Test
    fun prorootIsAvailableAsASeparateRootlessMode() {
        val state = RuntimeUiState(runtimeMode = RuntimeMode.Proroot)

        assertEquals(RuntimeMode.Proroot, state.runtimeMode)
        assertEquals(RootAccessState.NotRequired, state.rootAccess)
    }
}
