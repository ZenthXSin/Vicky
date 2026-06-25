import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "io.github.zenthxsin"
version = project.findProperty("version")?.toString() ?: "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":src:main:vicky-core"))

    implementation("org.mozilla:rhino:1.8.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.aallam.openai:openai-client:4.1.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir("kotlin")
        resources.srcDir("resources")
    }
    sourceSets.test {
        kotlin.srcDir("test/kotlin")
        resources.srcDir("test/resources")
    }
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates("io.github.zenthxsin", "vicky-script", project.version.toString())

    pom {
        name.set("Vicky Script")
        description.set("Dynamic TypeScript script engine for Vicky AI agent.")
        url.set("https://github.com/ZenthXSin/Vicky")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("zenthxsin")
                name.set("ZenthXSin")
                url.set("https://github.com/ZenthXSin")
            }
        }
        scm {
            url.set("https://github.com/ZenthXSin/Vicky")
            connection.set("scm:git:git://github.com/ZenthXSin/Vicky.git")
            developerConnection.set("scm:git:ssh://git@github.com/ZenthXSin/Vicky.git")
        }
    }
}
