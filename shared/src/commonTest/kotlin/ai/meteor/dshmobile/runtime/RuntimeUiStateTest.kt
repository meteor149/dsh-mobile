package ai.meteor.dshmobile.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeUiStateTest {
    @Test
    fun transientPhasesAreBusy() {
        assertTrue(RuntimeUiState(phase = RuntimePhase.Installing).isBusy)
        assertTrue(RuntimeUiState(phase = RuntimePhase.Starting).isBusy)
        assertTrue(RuntimeUiState(phase = RuntimePhase.Stopping).isBusy)
        assertFalse(RuntimeUiState(phase = RuntimePhase.Ready).isBusy)
    }
}
