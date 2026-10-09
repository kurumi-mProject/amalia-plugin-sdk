// Сборка примера «Напоминалка» в classes.dex.
//
// Почему Gradle: он есть на любом CI-раннере, умеет Kotlin JVM и не требует
// Android-плагина. Компилируем против android.jar (стабы Android) — ровно
// так, как это делает сам хост.
//
// Контракты хоста лежат в подпроекте `:contracts` и подключены как
// `compileOnly`: они нужны только чтобы код скомпилировался и НЕ должны
// попасть в jar плагина. Иначе в dex уехали бы копии классов хоста, и
// загрузка плагина упала бы на конфликте классов.
plugins {
    kotlin("jvm") version "2.0.21" apply false
}

// android.jar берём из Android SDK (ANDROID_HOME). Это стабы платформы:
// на устройстве наши классы подхватят настоящие android.* из системы, а при
// компиляции нужны только сигнатуры.
val androidHome: String = (
    System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: "${System.getProperty("user.home")}/Android/Sdk"
    ).trimEnd('/')

val androidApi: String = (findProperty("androidApi") as String?) ?: "34"

val androidJar: File = file("$androidHome/platforms/android-$androidApi/android.jar")
extra["androidJar"] = androidJar

if (!androidJar.isFile) {
    throw GradleException(
        "Не найден android.jar: $androidJar\n" +
            "Установите платформу через Android SDK:\n" +
            "  sdkmanager \"platforms;android-$androidApi\"\n" +
            "или задайте ANDROID_HOME и -PandroidApi=<версия>.",
    )
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
    }

    // JVM 11 — как в приложении: d8 принимает такой байткод без оговорок.
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    extensions.configure<JavaPluginExtension>("java") {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
