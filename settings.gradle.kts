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
        // Jellyfin's Maven for FFmpeg decoder with DTS/AC3/EAC3 support
        maven { url = uri("https://s01.oss.sonatype.org/content/repositories/releases/") }
    }
}

rootProject.name = "ProVideoPlayer"
include(":app")
