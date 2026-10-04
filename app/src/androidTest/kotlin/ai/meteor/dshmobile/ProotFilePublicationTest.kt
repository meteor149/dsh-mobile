package ai.meteor.dshmobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.meteor.dsh.runtime.DshInstaller
import ai.meteor.dsh.runtime.RuntimeMode
import ai.meteor.dsh.runtime.DshEnvironment
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProotFilePublicationTest {
    @Test fun createsReadsUpdatesAndProtectsExistingFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installer = DshInstaller(context)
        val mode = RuntimeMode.valueOf(InstrumentationRegistry.getArguments().getString("runtimeMode") ?: "Proot")
        // Install a changed application payload while retaining Ubuntu and user workspaces.
        val payload = installer.install()
        val storage = context.filesDir.toPath().resolve("linux-data/workspaces")
        Files.createDirectories(storage)
        val directory = Files.createTempDirectory(storage, "file-publication-test-")
        Files.createDirectory(directory.resolve("files"))
        val guestDirectory = "/workspace/${directory.fileName}/files"
        val script = """
            import assert from 'node:assert/strict';
            import { Context } from '/opt/dsh/node_modules/@deepseek-ai/cordis/lib/index.js';
            import { LocalFileSystem } from '/opt/dsh/node_modules/@deepseek-ai/dsh-fs-local/lib/index.js';
            import { mkdir, lstat, readdir, readFile, writeFile, symlink } from 'node:fs/promises';
            import { publishNewFile } from '/opt/dsh-mobile/compat/file-publication.mjs';
            const service = new LocalFileSystem(new Context(), {cwd:'$guestDirectory',diffBasisMaxBytes:65536});
            const target = await service.resolve('新建文件.txt');
            const first = await service.writeText(target, 'DSH 新建文件\n', {kind:'createIfAbsent'});
            assert.equal(first.operation, 'create');
            assert.equal(await service.readText(target), 'DSH 新建文件\n');
            assert.ok((await lstat('$guestDirectory/新建文件.txt')).isFile());
            await assert.rejects(service.writeText(target, 'overwrite', {kind:'createIfAbsent'}), {code:'FS_NOT_OBSERVED'});
            await service.writeText(target, 'updated', {kind:'replaceIfVersion',version:first.version});
            assert.equal(await service.readText(target), 'updated');
            await assert.rejects(service.writeText(target, 'stale', {kind:'replaceIfVersion',version:first.version}), {code:'FS_STALE_VERSION'});
            const parallel = await service.resolve('parallel.txt');
            const attempts = await Promise.allSettled(['one','two'].map(text=>service.writeText(parallel,text,{kind:'createIfAbsent'})));
            assert.equal(attempts.filter(result=>result.status==='fulfilled').length,1);
            assert.ok(['one','two'].includes(await service.readText(parallel)));
            // Exercise the publication race itself, independently of DSH's per-path lock.
            await writeFile('$guestDirectory/a','A'); await writeFile('$guestDirectory/b','B');
            const races = await Promise.allSettled(['a','b'].map(name=>publishNewFile('$guestDirectory/'+name,'$guestDirectory/race')));
            assert.equal(races.filter(result=>result.status==='fulfilled').length,1);
            assert.equal(races.find(result=>result.status==='rejected').reason.code,'EEXIST');
            assert.ok(['A','B'].includes(await readFile('$guestDirectory/race','utf8')));
            assert.ok((await readdir('$guestDirectory')).includes('race'));
            await mkdir('$guestDirectory/existing-dir');
            await symlink('missing', '$guestDirectory/dangling');
            for (const name of ['existing-dir','dangling']) {
              await writeFile('$guestDirectory/source','unchanged');
              await assert.rejects(publishNewFile('$guestDirectory/source','$guestDirectory/'+name), {code:'EEXIST'});
              assert.equal(await readFile('$guestDirectory/source','utf8'),'unchanged');
            }
            assert.ok((await lstat('$guestDirectory/existing-dir')).isDirectory());
            assert.ok((await lstat('$guestDirectory/dangling')).isSymbolicLink());
            const names = await readdir('$guestDirectory');
            assert.ok(!names.some(name=>name.endsWith('.tmpdir') || name.startsWith('.l2s')));
        """.trimIndent()
        val base = installer.command(payload, "test-token-".repeat(5), mode)
        val command = base.copy(
            arguments = listOf("/opt/node/bin/node", "--expose-internals", "--input-type=module", "-e", script),
            workingDirectory = "/workspace")
        try {
            val result = DshEnvironment(context).execute(command, mode)
            assertEquals(result.output, 0, result.exitCode)
        } finally { directory.toFile().deleteRecursively() }
    }
}
