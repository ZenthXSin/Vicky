plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "io.github.zenthxsin"
version = project.findProperty("version")?.toString() ?: "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":src:main:vicky-core"))

    implementation("com.aallam.openai:openai-client:4.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir("kotlin")
    }
}
