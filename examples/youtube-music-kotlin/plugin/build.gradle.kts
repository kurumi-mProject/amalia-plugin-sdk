plugins { kotlin("jvm") }

sourceSets.main {
    kotlin.srcDir(rootProject.file("plugin/src"))
}

val androidJar = rootProject.extra["androidJar"] as File
dependencies {
    compileOnly(project(":contracts"))
    // media3 AARs are resolved and extracted by the root build script.
    // okhttp and NewPipeExtractor are plain JARs (implementation = bundled into plugin jar).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.24.2")
}

// The legacy loader receives one DEX and does not resolve Maven dependencies.
// Make a self-contained jar while keeping host contracts/platform classes out.
tasks.named<Jar>("jar") {
    archiveBaseName.set("plugin")
    archiveVersion.set("")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("com/my/amali/**", "kotlin/**", "kotlinx/**")
    // Media3 is not supplied by the host and must remain in the plugin jar.
    // Do not exclude androidx here: this is the legacy trusted loader format.
}
