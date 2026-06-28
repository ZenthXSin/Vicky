import com.vanniktech.maven.publish.SonatypeHost

plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("com.gradleup.shadow") version "9.0.0-beta12"
    id("com.google.devtools.ksp")
    id("com.vanniktech.maven.publish") version "0.30.0"
    application
}

group = "io.github.zenthxsin"
version = project.findProperty("version")?.toString() ?: "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

configurations.all {
    resolutionStrategy {
        // 强制所有 ktor 模块统一版本，避免 openai-client / overflow-core 拉入不兼容版本
        force("io.ktor:ktor-client-core:3.4.3")
        force("io.ktor:ktor-client-okhttp:3.4.3")
        force("io.ktor:ktor-client-cio:3.4.3")
        force("io.ktor:ktor-client-content-negotiation:3.4.3")
        force("io.ktor:ktor-serialization-kotlinx-json:3.4.3")
        force("io.ktor:ktor-http:3.4.3")
        force("io.ktor:ktor-http-cio:3.4.3")
        force("io.ktor:ktor-network:3.4.3")
        force("io.ktor:ktor-network-tls:3.4.3")
        force("io.ktor:ktor-websockets:3.4.3")
        force("io.ktor:ktor-client-auth:3.4.3")
        force("io.ktor:ktor-client-logging:3.4.3")
        force("io.ktor:ktor-io:3.4.3")
        force("io.ktor:ktor-utils:3.4.3")
        force("io.ktor:ktor-events:3.4.3")
        force("io.ktor:ktor-sse:3.4.3")
        force("io.ktor:ktor-client-sse:3.4.3")
        force("io.ktor:ktor-client-encoding:3.4.3")
    }
}

dependencies {
    implementation(project(":src:main:vicky-core"))
    implementation(project(":src:main:vicky-script"))
    implementation(project(":src:main:vicky-vibe"))

    implementation("com.aallam.openai:openai-client:4.1.0")
    implementation("io.github.jbellis:jvector:3.0.6")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    implementation("io.ktor:ktor-client-okhttp:3.0.0")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.0")

    implementation("top.mrxiaom.mirai:overflow-core:1.1.0")

    ksp(project(":src:main:vicky-ksp"))

    testImplementation(kotlin("test"))
}

// --- KSP 稳定性保障 ---
afterEvaluate {
    // 1. 强制 kspKotlin 每次都执行，防止增量缓存误判 UP-TO-DATE 导致生成代码缺失
    tasks.findByName("kspKotlin")?.let { kspTask ->
        kspTask.outputs.upToDateWhen { false }

        // 2. 显式声明 compileKotlin 依赖 kspKotlin，确保生成代码先于编译
        tasks.findByName("compileKotlin")?.dependsOn(kspTask)
    }

    // 3. 将 KSP 生成的源码目录注册到 sourceSet，防止 Gradle 漏掉
    kotlin {
        sourceSets.named("main") {
            kotlin.srcDir("build/generated/ksp/main/kotlin")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.example.vicky.examples.ConsoleMainKt")
    applicationDefaultJvmArgs = listOf("--add-modules", "jdk.incubator.vector")
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
    failOnNoDiscoveredTests = false
    jvmArgs("--add-modules", "jdk.incubator.vector")
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
