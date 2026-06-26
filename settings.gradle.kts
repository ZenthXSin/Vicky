pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()   // KSP 插件托管在 Google Maven，必须加
    }
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
        id("com.google.devtools.ksp") version "2.3.9"
    }
}

rootProject.name = "Vicky"

include("src:main:vicky-core")
include("src:main:vicky-ksp")
include("src:main:vicky-script")
include("src:main:vicky-vibe")