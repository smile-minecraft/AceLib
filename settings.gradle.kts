pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    // foojay-resolver-convention：自動從 foojay 下載缺少的 JDK toolchain
    // （本機只有 Java 21/23，需要 Java 25 來編譯 Paper 26.1.2 API）
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "AceLib"