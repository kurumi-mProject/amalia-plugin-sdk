plugins {
    kotlin("jvm") version "1.9.25" apply false
}

val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: "${System.getProperty("user.home")}/Android/Sdk"
val androidApi = providers.gradleProperty("androidApi").getOrElse("35")
val androidJar = file("$androidHome/platforms/android-$androidApi/android.jar")
extra["androidJar"] = androidJar
if (!androidJar.isFile) error("Missing $androidJar; install platforms;android-$androidApi or set ANDROID_HOME")

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
