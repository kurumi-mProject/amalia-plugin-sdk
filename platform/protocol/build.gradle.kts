plugins {
    `maven-publish`
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

// Immutable author coordinates; a different ABI requires a new sdkVersion.
group = "org.amalia.platform"
version = providers.gradleProperty("sdkVersion").getOrElse("0.2.0-SNAPSHOT")
java { withSourcesJar(); withJavadocJar() }
publishing {
    publications {
        create<MavenPublication>("sdk") {
            from(components["java"])
            artifactId = "amalia-protocol"
            pom {
                name.set("Amalia protocol SDK")
                description.set("Independent public Amalia plugin protocol API")
                url.set("https://github.com/kurumi-mProject/amalia")
                licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
            }
        }
    }
    repositories {
        maven {
            name = "sdkDistribution"
            url = rootProject.layout.buildDirectory.dir("sdk-repository").get().asFile.toURI()
        }
    }
}
