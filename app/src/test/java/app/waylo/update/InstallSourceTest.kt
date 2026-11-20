package app.waylo.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallSourceTest {
    private val play = "com.android.vending"

    @Test fun eitherRecordNamingPlayIsACarSetup() {
        assertTrue(InstallSource.isCarSetup(play, play))
        assertTrue("adb install -i: the installer says Play, the shell started it", InstallSource.isCarSetup(play, "com.android.shell"))
        assertTrue("started as Play, installer not recorded", InstallSource.isCarSetup(null, play))
    }

    @Test fun anOrdinaryInstallIsNot() {
        assertFalse(InstallSource.isCarSetup(null, null))
        assertFalse(InstallSource.isCarSetup("org.fdroid.fdroid", "org.fdroid.fdroid"))
        assertFalse(InstallSource.isCarSetup("app.waylo", "app.waylo"))
    }
}
