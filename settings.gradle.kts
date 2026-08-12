// Cribbed directly from japanglify/settings.gradle.kts for FreeBSD Linuxulator + AGP compatibility.
// Spoof os.name to Linux *before* pluginManagement so AGP resolves Linux aapt2/build-tools.
// Real tools are brandelf'ed by scripts/prepare-freebsd-build.sh so the Linuxulator executes them.

val realOs = System.getProperty("os.name").orEmpty()
val isBsdHost = realOs.equals("FreeBSD", ignoreCase = true) ||
    realOs.equals("OpenBSD", ignoreCase = true) ||
    realOs.equals("NetBSD", ignoreCase = true)

if (isBsdHost) {
    System.setProperty("os.name", "Linux")
    System.setProperty("umassisted.real.os", realOs)
    System.setProperty("umassisted.freebsd", "true")
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "UMAssisted"
include(":app")
