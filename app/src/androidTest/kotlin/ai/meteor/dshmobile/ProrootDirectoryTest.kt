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
class ProrootDirectoryTest {
    @Test fun enumeratesBoundAndRootfsPathsThroughNativeDirectoryApis() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parent = context.filesDir.toPath().resolve("linux-data/workspaces")
        Files.createDirectories(parent)
        val fixture = Files.createTempDirectory(parent, "directory-test-")
        val cache = Files.createTempDirectory(context.cacheDir.toPath(), "directory-test-")
        val path = "/workspace/${fixture.fileName}"
        val installer = DshInstaller(context)
        val payload = requireNotNull(installer.probe())
        val script = """
            const assert = require('node:assert/strict');
            const fs = require('node:fs');
            const fsp = require('node:fs/promises');
            const {execFileSync} = require('node:child_process');
            const root = '$path';
            (async()=>{
              const names = ['文件.txt','.hidden','subdir','link','dangling'];
              const sorted = names.sort();
              for(const directory of [root, '$cache']) {
                fs.writeFileSync(directory+'/文件.txt','preserved');
                fs.writeFileSync(directory+'/.hidden','hidden');
                fs.mkdirSync(directory+'/subdir');
                fs.symlinkSync('文件.txt',directory+'/link');
                fs.symlinkSync('absent',directory+'/dangling');
                assert.deepEqual(fs.readdirSync(directory).sort(),sorted);
                assert.deepEqual((await fsp.readdir(directory)).sort(),sorted);
                const dirents = await fsp.readdir(directory,{withFileTypes:true});
                assert.ok(dirents.find(e=>e.name==='subdir').isDirectory());
                assert.ok(dirents.find(e=>e.name==='文件.txt').isFile());
                assert.ok(dirents.find(e=>e.name==='dangling').isSymbolicLink());
                assert.deepEqual(fs.readdirSync(directory,{encoding:'buffer'}).map(b=>b.toString()).sort(),sorted);
                const stream=await fsp.opendir(directory);
                const entries=[]; for await(const entry of stream) entries.push(entry.name);
                assert.deepEqual(entries.sort(),sorted);
                assert.equal(fs.readFileSync(directory+'/文件.txt','utf8'),'preserved');
              }
              const cwd=process.cwd();
              process.chdir(root);
              assert.deepEqual(fs.readdirSync('.').sort(),sorted);
              const relative = await fsp.readdir('subdir');
              assert.deepEqual(relative,[]);
              process.chdir(cwd);
              assert.ok((await fsp.readdir('/usr')).includes('bin'));
              assert.ok((await fsp.readdir('/opt/dsh/node_modules')).includes('@deepseek-ai'));
              assert.ok(Array.isArray(await fsp.readdir('/root')));
              assert.ok(Array.isArray(await fsp.readdir('/dsh-home')));
              // A fresh descendant must inherit the same namespace mapping.
              execFileSync('/opt/node/bin/node',['-e',
                'const fs=require("node:fs");require("node:assert/strict").deepEqual(fs.readdirSync(process.argv[1]).sort(),JSON.parse(process.argv[2]))',root,JSON.stringify(sorted)],{cwd:root});
              // The physical app path remains stable if another libc operation sees it.
              assert.deepEqual(fs.readdirSync('$fixture').sort(),sorted);
              assert.throws(()=>fs.readdirSync(root+'/absent'),{code:'ENOENT'});
              assert.throws(()=>fs.readdirSync(root+'/文件.txt'),{code:'ENOTDIR'});
            })().catch(e=>{console.error(e);process.exitCode=1});
        """.trimIndent()
        try {
            val command = installer.command(payload, "test-token-".repeat(5), RuntimeMode.Proroot)
                .copy(arguments=listOf("/opt/node/bin/node","-e",script))
            val result = DshEnvironment(context).execute(command,RuntimeMode.Proroot)
            assertEquals(result.output,0,result.exitCode)
        } finally {
            fixture.toFile().deleteRecursively()
            cache.toFile().deleteRecursively()
        }
    }
}
