package ai.meteor.dshmobile.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootAccessControllerTest {
    @Test
    fun onlyUidZeroOutputCountsAsRoot() {
        assertTrue(isRootUidOutput("0\n"))
        assertTrue(isRootUidOutput("root manager message\n 0 \n"))
        assertFalse(isRootUidOutput("2000\n"))
        assertFalse(isRootUidOutput("permission denied\n"))
    }
}
