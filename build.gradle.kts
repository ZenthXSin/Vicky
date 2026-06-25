import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("com.gradleup.shadow") version "9.0.0-beta12"
    id("com.google.devtools.ksp")
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "io.github.zenthxsin"
version = project.findProperty("version")?.toString() ?: "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":src:main:vicky-core"))
    implementation(project(":src:main:vicky-script"))

    implementation("com.aallam.openai:openai-client:4.1.0")

    implementation("io.ktor:ktor-client-okhttp:3.0.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")

    implementation("top.mrxiaom.mirai:overflow-core:1.1.0")

    ksp(project(":src:main:vicky-ksp"))

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    archiveBaseName.set("Vicky")
    archiveVersion.set(project.version.toString())
}

tasks.shadowJar {
    archiveBaseName.set("Vicky")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    mergeServiceFiles()
    manifest {
        attributes("Main-Class" to "org.example.vicky.examples.ConsoleMainKt")
    }
}

// Shadow 9.x beta 在 Gradle 9 下生成 application distribution 任务仍有兼容问题，
// 而我们只需要 shadowJar，因此禁用这些发行任务。
tasks.matching { it.name in setOf("startShadowScripts", "shadowDistTar", "shadowDistZip") }
    .configureEach { enabled = false }

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates("io.github.zenthxsin", "vicky", project.version.toString())

    pom {
        name.set("Vicky")
        description.set("Mindustry Vicky AI agent framework with OneBot integration.")
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
