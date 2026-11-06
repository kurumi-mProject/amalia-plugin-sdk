// Independent SDK/runtime build: no Android SDK or application checkout is required.
pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.2.10"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
rootProject.name = "amalia-platform"
include(":protocol", ":runtime")
