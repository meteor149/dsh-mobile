package ai.meteor.dshmobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.meteor.dsh.runtime.DshInstaller
import ai.meteor.dsh.runtime.RuntimeMode
import ai.meteor.dsh.runtime.DshEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProrootCompatibilityTest {
    @Test fun loadsBuiltinAddonWithoutMaterializingNativeCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = DshInstaller(context)
        val payload = requireNotNull(installer.probe())
        val script = """
            try { require('/opt/dsh/node_modules/node-addon-require-builtin'); }
            catch(e) {
              const clean = e => ({message:e.message, attempts:e.attempts?.map(a=>({message:a.message,error:a.error && clean(a.error)}))});
              process.stderr.write(JSON.stringify(clean(e)));process.exitCode=1;
            }
        """.trimIndent()
        val base = installer.command(payload, "test-token-".repeat(5)).copy(
            arguments = listOf("/opt/node/bin/node", "--expose-internals", "-e", script))
        val environment = DshEnvironment(context)
        val baseline = environment.execute(base.copy(environment = base.environment - "NARB_DISABLE_NATIVE_CACHE"), RuntimeMode.Proroot)
        val freshDirectory = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "native-cache-verification-")
        try {
            val fresh = environment.execute(base.copy(
                environment = (base.environment - "NARB_DISABLE_NATIVE_CACHE") + ("NARB_NATIVE_CACHE_DIR" to "/addon-cache-test"),
                bindings = base.bindings + ("/addon-cache-test" to freshDirectory)), RuntimeMode.Proroot)
            assertEquals("Fresh cache: ${fresh.output}", 0, fresh.exitCode)
        } finally { freshDirectory.toFile().deleteRecursively() }
        val uncached = environment.execute(base, RuntimeMode.Proroot)
        assertEquals("Baseline: ${baseline.output}\nUncached: ${uncached.output}", 0, uncached.exitCode)
    }
}
