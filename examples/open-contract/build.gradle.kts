plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":runtime"))
}

application { mainClass.set("org.example.counter.MainKt") }
