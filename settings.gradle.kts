pluginManagement {
    repositories {
        maven { url = uri("https://maven-central-eu.storage-download.googleapis.com/maven2/") }
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven-central-eu.storage-download.googleapis.com/maven2/") }
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
rootProject.name = "WebBox"
include(":app")
