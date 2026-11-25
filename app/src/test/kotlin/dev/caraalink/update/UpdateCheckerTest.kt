package dev.caraalink.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun onlyANewerReleaseIsAnUpdate() {
        assertTrue(UpdateChecker.isNewer(latest = "1.3.0", installed = "1.2.1"))
        assertFalse(UpdateChecker.isNewer(latest = "1.2.1", installed = "1.3.0"))
        assertFalse(UpdateChecker.isNewer(latest = "1.3.0", installed = "1.3.0"))
        assertTrue(UpdateChecker.isNewer(latest = "1.10.0", installed = "1.9.9"))
        assertTrue(UpdateChecker.isNewer(latest = "1.3.1", installed = "1.3"))
    }
}
