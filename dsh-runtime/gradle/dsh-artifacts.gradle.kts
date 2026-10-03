import groovy.json.JsonSlurper
import java.security.MessageDigest

val dist = project.file("runtime/dist")
val output = layout.buildDirectory.dir("generated/runtime/assets/dsh")
tasks.register("prepareRuntimeAssets") {
    inputs.files(fileTree(dist))
    inputs.file(project.file("runtime/manifest/unavailable.json"))
    outputs.dir(output)
    doLast {
        val target = output.get().asFile
        delete(target); target.mkdirs()
        val manifest = dist.resolve("dsh-manifest.json").takeIf { it.isFile }
            ?: project.file("runtime/manifest/unavailable.json")
        val data = JsonSlurper().parse(manifest) as Map<*, *>
        check(data["schemaVersion"] == 1)
        if (data["available"] == true) {
            val archive = data["archive"] as Map<*, *>
            val name = archive["file"] as String
            check(name.matches(Regex("[A-Za-z0-9._-]+")))
            val source = dist.resolve(name)
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } == archive["sha256"])
            source.copyTo(target.resolve(name), overwrite = true)
        }
        manifest.copyTo(target.resolve("dsh-manifest.json"), overwrite = true)
    }
}
