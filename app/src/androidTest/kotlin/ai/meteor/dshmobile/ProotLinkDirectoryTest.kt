package ai.meteor.dshmobile

import ai.meteor.dsh.runtime.DshEnvironment
import ai.meteor.dsh.runtime.RuntimeMode
import ai.meteor.ubuntu.runtime.UbuntuCommand
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the Maven engine directly, without DSH's publication adapter. */
@RunWith(AndroidJUnit4::class)
class ProotLinkDirectoryTest {
    @Test fun hardLinksRemainReadableAndEnumerateAsFilesAfterRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".smoke"))
        val storage = context.filesDir.toPath().resolve("linux-data/workspaces")
        Files.createDirectories(storage)
        val directory = Files.createTempDirectory(storage, "snapshot-links-")
        val guest = "/workspace/${directory.fileName}"
        val environment = DshEnvironment(context)
        val create = """
            import os,pathlib
            d=pathlib.Path('$guest')
            (d/'parent').mkdir()
            (d/'parent/original').write_text('snapshot payload')
            os.link(d/'parent/original',d/'parent/alias')
            os.symlink('alias',d/'parent/ordinary')
            os.symlink('missing',d/'parent/dangling')
        """.trimIndent()
        val verify = """
            import os,pathlib,shutil
            root=pathlib.Path('$guest');d=root/'parent'
            for name in ['original','alias']:
                assert (d/name).read_text()=='snapshot payload'
            entries={entry.name:entry for entry in os.scandir(d)}
            for name in ['original','alias']:
                assert entries[name].is_file(follow_symlinks=False),name
                assert not entries[name].is_symlink(),name
                assert entries[name].inode()==(d/name).stat().st_ino,name
            assert entries['ordinary'].is_symlink()
            assert entries['dangling'].is_symlink()
            moved=root/'renamed';d.rename(moved)
            entries={entry.name:entry for entry in os.scandir(moved)}
            assert entries['alias'].is_file(follow_symlinks=False)
            assert entries['ordinary'].is_symlink()
            assert (moved/'alias').read_text()=='snapshot payload'
            shutil.rmtree(root)
        """.trimIndent()
        try {
            for (script in listOf(create, verify)) {
                val result = environment.execute(UbuntuCommand(listOf("/usr/bin/python3", "-I", "-c", script)), RuntimeMode.Proot)
                assertEquals(result.output, 0, result.exitCode)
            }
        } finally { environment.stop(); directory.toFile().deleteRecursively() }
    }
}
