plugins { kotlin("jvm") }

sourceSets.main {
    kotlin.srcDir(rootProject.file("../../amalia-sdk/sdk/api/minimal"))
}

val androidJar = rootProject.extra["androidJar"] as File
dependencies { compileOnly(files(androidJar)) }
