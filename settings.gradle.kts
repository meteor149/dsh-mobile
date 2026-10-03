pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val ubuntuSources = file(providers.environmentVariable("UBUNTU_SOURCE_DIR").getOrElse(".."))

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("USE_LOCAL_UBUNTU_MAVEN").getOrElse("false").toBoolean()) {
            maven { url = uri(ubuntuSources.resolve("android-ubuntu-runtime/build/maven-repository")) }
            maven { url = uri(ubuntuSources.resolve("android-ubuntu-image/build/maven-repository")) }
        }
        providers.gradleProperty("UBUNTU_MAVEN_URL").orNull?.let { address -> maven { url = uri(address) } }
        google()
        mavenCentral()
    }
}

rootProject.name = "dsh-mobile"
include(":app")
include(":shared")
include(":dsh-runtime")

// Local source substitution is explicitly opt-in.
if (providers.gradleProperty("USE_LOCAL_UBUNTU_LIBRARIES").getOrElse("false").toBoolean()) {
    listOf("android-ubuntu-runtime" to "ubuntu-runtime", "android-ubuntu-image" to "ubuntu-image").forEach { (repository, artifact) ->
        val checkout = ubuntuSources.resolve(repository)
        if (checkout.resolve("settings.gradle.kts").isFile) {
            includeBuild(checkout) {
                dependencySubstitution {
                    substitute(module("io.github.meteor149:$artifact")).using(project(":"))
                }
            }
        }
    }
}
