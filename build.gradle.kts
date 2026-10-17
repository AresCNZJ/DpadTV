// 与 WebView 版共用同一套已验证的工具链：AGP 9.3.0 / Gradle 9.5.0 / JDK 25。
//
// 注意：AGP 9 自带 Kotlin 支持，**不能再叠加 org.jetbrains.kotlin.android**，
// 否则会报 "Failed to apply plugin 'org.jetbrains.kotlin.android'"。
// .kt 源码由 AGP 内置的 Kotlin 编译链处理。
plugins {
    id("com.android.application") version "9.3.0" apply false
}
