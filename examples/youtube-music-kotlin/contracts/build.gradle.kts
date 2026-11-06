plugins { kotlin("jvm") }

sourceSets.main {
    kotlin.srcDir(rootProject.file("../../sdk/api/minimal"))
}

val androidJar = rootProject.extra["androidJar"] as File
dependencies { compileOnly(files(androidJar)) }
