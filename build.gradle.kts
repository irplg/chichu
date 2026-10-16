// AGP 9.0 起内置 Kotlin 支持，无需再应用 org.jetbrains.kotlin.android 插件。
// 内置 Kotlin 默认使用 KGP 2.2.10，这里通过 buildscript 类路径升级到 2.4.21，
// 以匹配 Compose 编译器插件与 kotlinx.serialization 插件的版本。
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
