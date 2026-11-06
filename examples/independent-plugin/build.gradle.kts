plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
val sdkVersion = providers.gradleProperty("amaliaSdkVersion").getOrElse("0.2.0-SNAPSHOT")
dependencies { compileOnly("org.amalia.platform:amalia-protocol:$sdkVersion") }
