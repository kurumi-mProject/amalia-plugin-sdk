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

// runtime's standalone dependency mapping expects this root name.
rootProject.name = "amalia-platform"
include(":protocol", ":runtime")
project(":protocol").projectDir = file("../../platform/protocol")
project(":runtime").projectDir = file("../../platform/runtime")
