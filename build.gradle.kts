plugins {
    // ★ 1.0.102：Kotlin 1.9.24 -> 2.3.20、AGP 8.5.2 -> 8.13.2。
    //   miuix 0.8.x（含 WindowDialog）用 Kotlin 2.3.20 编译，且其 Compose 依赖
    //   （CMP 1.10 -> androidx compose 1.10）要求 compileSdk 36，因此整套工具链一起升。
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    // Kotlin 2.x 起 Compose 编译器随 Kotlin 一起发（不再用 composeOptions.extensionVersion）
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}