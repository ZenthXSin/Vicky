plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    id("com.gradleup.shadow") version "9.0.0-beta12"
    `maven-publish`
}

group = "org.example.vicky"
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
    implementation("ch.qos.logback:logback-classic:1.5.12")

    implementation("top.mrxiaom.mirai:overflow-core:1.1.0")

    // 内置语义模型（DJL + HuggingFace sentence-transformers，本地推理）
    implementation("ai.djl:api:0.31.1")
    implementation("ai.djl.huggingface:tokenizers:0.30.0")
    implementation("ai.djl.pytorch:pytorch-engine:0.30.0")
    implementation("ai.djl.pytorch:pytorch-model-zoo:0.30.0")

    // 向量存储（Qdrant REST API，通过 Ktor HTTP 客户端调用）

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

// JDK 17+ 需要显式 --add-opens 才能反射注入进程环境变量
// （BuiltinEmbeddingClient.configureProxy 需要给原生 tokenizer 注入 HTTPS_PROXY）
tasks.withType<JavaExec>().configureEach {
    jvmArgs(
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
    )
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "vicky"
            version = project.version.toString()
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY")}")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
