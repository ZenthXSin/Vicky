plugins {
    kotlin("jvm")
    id("com.google.devtools.ksp")
}

group = "org.example.vicky"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.9")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup:kotlinpoet:2.3.0")
    implementation("com.squareup:kotlinpoet-ksp:2.3.0")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

ksp {
    arg("vicky.ksp.processor", "org.example.vicky.annotation.VickyToolProcessorProvider")
}