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
        // Spotify's App Remote SDK auth module is published here.
        maven { url = uri("https://maven.spotify.com/releases") }
    }
}

rootProject.name = "SyncListen"
include(":app")
