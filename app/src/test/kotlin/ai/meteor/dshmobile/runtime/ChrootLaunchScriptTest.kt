package ai.meteor.dshmobile.runtime

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ChrootLaunchScriptTest {
    @Test
    fun launchScriptRequiresRootMountsRuntimeDataAndCleansUp() {
        val home = Paths.get("/data/user/0/app/files/linux-data/home")
        val script = chrootLaunchScript(
            rootfs = Paths.get("/data/user/0/app/files/runtime/rootfs"),
            home = home,
            dshHome = Paths.get("/data/user/0/app/files/linux-data/dsh-home"),
            workspaces = Paths.get("/data/user/0/app/files/linux-data/workspaces"),
            pidFile = Paths.get("/data/user/0/app/cache/chroot/session.pid"),
            token = "token-value",
            guestCommand = "/usr/local/bin/dsh-mobile-gateway",
            appUid = 10123,
            appGid = 10123,
            appPid = 4567,
        )

        assertContains(script, "if [ \"\$(id -u)\" != \"0\" ]")
        assertContains(script, "mount --bind /dev \"\$ROOTFS/dev\"")
        assertContains(script, "mount --bind /dev/pts \"\$ROOTFS/dev/pts\"")
        assertContains(script, "mount -t proc proc \"\$ROOTFS/proc\"")
        assertContains(script, "mount --bind /sys \"\$ROOTFS/sys\"")
        assertContains(script, "HOME_SOURCE=${shellQuote(home.toString())}")
        assertContains(script, "mount --bind \"\$HOME_SOURCE\" \"\$ROOTFS/root\"")
        assertContains(script, "umount \"\$ROOTFS/workspace\"")
        assertContains(script, "APP_OWNER='10123:10123'")
        assertContains(script, "APP_PID='4567'")
        assertContains(script, "while kill -0 \"\$APP_PID\"")
        assertContains(script, "if [ \"\${1:-}\" = \"--isolated\" ]")
        assertContains(script, "mount --make-rprivate /")
        assertContains(script, "chown -R \"\$APP_OWNER\" \"\$ROOTFS\"")
        assertContains(script, "chroot \"\$ROOTFS\" /usr/bin/env -i")
        assertContains(script, "DSH_MOBILE_TOKEN='token-value'")
    }

    @Test
    fun shellArgumentsAreSingleQuoteEscaped() {
        assertEquals("'a'\\''b'", shellQuote("a'b"))
    }

    @Test
    fun stopCommandRejectsNonNumericPidValues() {
        val command = buildChrootStopCommand(Paths.get("/cache/session.pid"))

        assertContains(command, "case \"\$PID\" in *[!0-9]*|'') exit 1;; esac")
        assertContains(command, "kill -TERM \"\$PID\"")
    }
}
