import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "io.github.zenthxsin"
version = project.findProperty("version")?.toString() ?: "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.aallam.openai:openai-client:4.1.0")

    implementation("io.ktor:ktor-client-okhttp:3.0.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("io.modelcontextprotocol:kotlin-sdk-client:0.13.0")
    implementation("io.ktor:ktor-client-cio:3.1.0")

    implementation("ch.qos.logback:logback-classic:1.5.13")
}

kotlin {
    jvmToolchain(21)
    sourceSets.main {
        kotlin.srcDir("kotlin")
    }
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates("io.github.zenthxsin", "vicky-core", project.version.toString())

    pom {
        name.set("Vicky Core")
        description.set("Core framework for Vicky AI agent.")
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
