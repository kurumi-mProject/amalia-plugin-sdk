pluginManagement {
    repositories { mavenCentral(); gradlePluginPortal() }
    plugins { id("org.jetbrains.kotlin.jvm") version "2.2.10" }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri(providers.gradleProperty("amaliaSdkRepository").getOrElse("sdk-repository")) }
        mavenCentral()
    }
}
rootProject.name = "amalia-plugin"
