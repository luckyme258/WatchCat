// 只声明插件版本，不在这里配置构建（配置在各模块的 build.gradle.kts 里）。
// 版本口径见《安卓开发规范》W13：AGP 8.11.1 / Kotlin 2.1.0 / Gradle 8.13。
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
