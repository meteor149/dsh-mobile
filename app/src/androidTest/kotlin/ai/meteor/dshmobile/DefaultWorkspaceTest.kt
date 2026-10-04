package ai.meteor.dshmobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.meteor.dsh.runtime.DshInstaller
import ai.meteor.dsh.runtime.RuntimeMode
import ai.meteor.dsh.runtime.DshEnvironment
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises first-use initialization with a fresh profile, without changing user profiles. */
@RunWith(AndroidJUnit4::class)
class DefaultWorkspaceTest {
    @Test fun createsWritableDefaultWorkspaceAndReusesItAfterRestartInBothModes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = DshInstaller(context)
        val payload = installer.install()
        val storage = context.filesDir.toPath().resolve("linux-data/workspaces")
        Files.createDirectories(storage)
        val fixture = Files.createTempDirectory(storage, "default-workspace-test-")
        val home = Files.createDirectory(fixture.resolve("profile"))
        val workspace = storage
        val markerName = "${fixture.fileName}-persistent.txt"
        val createdFiles = mutableListOf<java.nio.file.Path>()
        val environment = DshEnvironment(context)
        val token = UUID.randomUUID().toString() + UUID.randomUUID()
        var firstId: String? = null
        try {
            for (mode in listOf(RuntimeMode.Proot, RuntimeMode.Proroot, RuntimeMode.Proot)) {
                val base = installer.command(payload, token, mode)
                val command = base.copy(bindings = base.bindings + ("/dsh-home" to home))
                val ready = CompletableDeferred<String>()
                environment.start(command, mode, onLog = { line ->
                    if (line.startsWith("dsh-mobile gateway: ")) ready.complete(line.substringAfter("dsh-mobile gateway: "))
                }, onExit = { code ->
                    if (!ready.isCompleted) ready.completeExceptionally(IllegalStateException("Gateway exited: $code"))
                })
                try {
                    val origin = withTimeout(90_000) { ready.await() }
                    val cookie = (URL("$origin/?token=$token").openConnection() as HttpURLConnection).let { connection ->
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 15000
                        connection.readTimeout = 15000
                        try {
                            assertEquals(302, connection.responseCode)
                            requireNotNull(connection.getHeaderField("Set-Cookie")).substringBefore(';')
                        } finally { connection.disconnect() }
                    }
                    val connection = URL("$origin/api/workspace/initializeDefault").openConnection() as HttpURLConnection
                    val result = try {
                        connection.requestMethod = "POST"
                        connection.connectTimeout = 15000
                        connection.readTimeout = 15000
                        connection.setRequestProperty("Cookie", cookie)
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.doOutput = true
                        val body = JSONObject().put("type", "client-request").put("rpcId", "workspace-regression")
                            .put("method", "workspace/initializeDefault").put("payload", JSONObject().put("args", JSONObject()))
                        connection.outputStream.use { it.write(body.toString().toByteArray()) }
                        assertEquals(200, connection.responseCode)
                        JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("result")
                    } finally { connection.disconnect() }
                    assertTrue("${mode.name}: $result", result.getBoolean("ok"))
                    val record = result.getJSONObject("value").getJSONObject("workspace")
                    assertEquals("/workspace/deepseek-harness/default-workspace", record.getString("path"))
                    val browse = URL("$origin/api/directoryPicker/list").openConnection() as HttpURLConnection
                    val listing = try {
                        browse.requestMethod = "POST"
                        browse.connectTimeout = 15000
                        browse.readTimeout = 15000
                        browse.setRequestProperty("Cookie", cookie)
                        browse.setRequestProperty("Content-Type", "application/json")
                        browse.doOutput = true
                        val body = JSONObject().put("type", "client-request").put("rpcId", "directory-regression")
                            .put("method", "directoryPicker/list").put("payload", JSONObject().put("args",
                                JSONObject().put("path", record.getString("path"))))
                        browse.outputStream.use { it.write(body.toString().toByteArray()) }
                        assertEquals(200, browse.responseCode)
                        JSONObject(browse.inputStream.bufferedReader().use { it.readText() }).getJSONObject("result")
                    } finally { browse.disconnect() }
                    assertTrue("${mode.name}: ${listing.optJSONObject("error")}", listing.getBoolean("ok"))
                    assertTrue(listing.has("value"))
                    val id = record.getString("workspaceId")
                    if (firstId == null) firstId = id else assertEquals(firstId, id)
                    val directory = workspace.resolve("deepseek-harness/default-workspace")
                    assertTrue(Files.isDirectory(directory))
                    val marker = directory.resolve(markerName)
                    if (marker !in createdFiles) createdFiles.add(marker)
                    val content = "Default workspace survives restart"
                    if (Files.exists(marker)) assertEquals(content, String(Files.readAllBytes(marker)))
                    else Files.write(marker, content.toByteArray())
                    // Use the same DSH filesystem backend as agent tools, within the new workspace.
                    val script = """
                        import assert from 'node:assert/strict';
                        import { Context } from '/opt/dsh/node_modules/@deepseek-ai/cordis/lib/index.js';
                        import { LocalFileSystem } from '/opt/dsh/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js';
                        const fs = new LocalFileSystem(new Context(), {cwd:'/workspace/deepseek-harness/default-workspace',diffBasisMaxBytes:65536});
                        const target = await fs.resolve('${fixture.fileName}-${mode.name}-created.txt');
                        if (!await fs.stat(target)) await fs.writeText(target,'created in default workspace',{kind:'createIfAbsent'});
                        assert.equal(await fs.readText(target),'created in default workspace');
                    """.trimIndent()
                    val written = DshEnvironment(context).execute(command.copy(
                        arguments = listOf("/opt/node/bin/node", "--expose-internals", "--input-type=module", "-e", script)), mode)
                    val writtenPath = directory.resolve("${fixture.fileName}-${mode.name}-created.txt")
                    if (writtenPath !in createdFiles) createdFiles.add(writtenPath)
                    assertEquals(written.output, 0, written.exitCode)
                } finally { environment.stop() }
            }
        } finally {
            environment.stop()
            createdFiles.forEach(Files::deleteIfExists)
            fixture.toFile().deleteRecursively()
        }
    }
}
