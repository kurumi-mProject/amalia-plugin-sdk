// Music Player plugin — сборка в classes.dex
//
// Gradle компилирует Kotlin против android.jar (стабы Android) и
// контрактов хоста (compileOnly). Потом d8 конвертирует jar → dex.
//
// ВАЖНО: контракты хоста (com.my.amali.plugin.*) НЕ попадают в dex —
// они уже есть в приложении. Иначе будет конфликт классов при загрузке.

plugins {
    kotlin("jvm") version "1.9.25" apply false
}

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
            "или задайте ANDROID_HOME и -PandroidApi=<версия>."
    )
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
    }

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
