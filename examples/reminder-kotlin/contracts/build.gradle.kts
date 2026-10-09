// Контракты хоста — дословная референс-копия из SDK.
//
// Отдельный подпроект ради одного: его код не должен попасть в jar плагина.
// Хост уже содержит эти классы, и дубликат в dex — ошибка загрузки, а не
// «страховка».
plugins {
    kotlin("jvm")
}

sourceSets {
    main {
        kotlin.srcDir(rootProject.file("../../sdk/api/minimal"))
    }
}

@Suppress("UNCHECKED_CAST")
val androidJar = rootProject.extra["androidJar"] as File

dependencies {
    // android.content.Context, android.Manifest — стабы платформы.
    compileOnly(files(androidJar))
}
