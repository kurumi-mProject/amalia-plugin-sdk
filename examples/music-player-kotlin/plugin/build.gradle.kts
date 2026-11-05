plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
}
sourceSets.main {
    kotlin.srcDir(rootProject.file("plugin/src"))
    // Keep the assets/ prefix: the same entries are copied to the final package.
    resources.srcDir(rootProject.projectDir)
    resources.include("assets/**")
}
dependencies {
    compileOnly(project(":contracts"))
}
tasks.named<Jar>("jar") {
    archiveBaseName.set("plugin")
    archiveVersion.set("")
    archiveClassifier.set("")
    exclude("com/my/amali/**", "kotlin/**", "kotlinx/**", "androidx/**")
}
