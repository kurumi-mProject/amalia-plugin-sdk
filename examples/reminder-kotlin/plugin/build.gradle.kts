// Сам плагин. В jar попадают только его классы: контракты хоста подключены
// как compileOnly и в артефакт не уезжают.
plugins {
    kotlin("jvm")
}

sourceSets {
    main {
        kotlin.srcDir(rootProject.file("src"))
    }
}

@Suppress("UNCHECKED_CAST")
val androidJar = rootProject.extra["androidJar"] as File

dependencies {
    // Контракты хоста: нужны на компиляции, в dex не попадают.
    compileOnly(project(":contracts"))
    // android.content.Context, android.R.drawable.* — стабы платформы.
    compileOnly(files(androidJar))
    // Корутины — используются в ReminderStore (withContext/Dispatchers).
    // Kotlin stdlib хост уже несёт, а coroutines-core нужно включить в dex.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

// Имя артефакта фиксируем: workflow не должен угадывать версию проекта.
// Явно исключаем классы хоста — на случай если Gradle всё же включит их.
tasks.named<Jar>("jar") {
    archiveBaseName.set("plugin")
    archiveVersion.set("")
    archiveClassifier.set("")
    exclude("com/my/amali/**")
}
