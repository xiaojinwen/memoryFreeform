import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ★ APK 签名：从项目根目录 keystore.properties 读取（该文件含密码，已被 .gitignore 忽略）。
//   密钥库在 E:\project\apk-keys\app.jks，别名 xjwkey。debug / release 统一用同一签名，
//   保证 assembleDebug 与 assembleRelease 产物可互相覆盖安装（adb install -r）。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "xiaojw.memoryFreeform"
    // ★ 1.0.102：compose 1.10（miuix 0.8.x 的依赖）要求 compileSdk 36
    compileSdk = 36

    defaultConfig {
        applicationId = "xiaojw.memoryFreeform"
        minSdk = 26
        targetSdk = 34
        // 版本号约定：每次 fix 提交递增；versionCode 取 fix 序号（单调递增，Android 升级要求更大），
        // versionName 用 "1.0.<fix号>"，便于从设置/APK 名识别当前装的版本。
        //   fix65 -> 65 / "1.0.65"；fix66 -> 66 / "1.0.66"；fix67 -> 67 / "1.0.67"，以此类推。
        versionCode = 149
        versionName = "1.0.149"
    }

    signingConfigs {
        if (keystoreProps["storeFile"] != null) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps["storeFile"] != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // debug 也用正式签名，避免与已安装的 release 包签名不一致导致覆盖安装失败
            if (keystoreProps["storeFile"] != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // ★ APK 输出文件名统一为 memory-freeform_<版本号>.apk（debug / release 均生效）。
    //   debug 包带 -debug 后缀便于区分，如 memory-freeform_1.0.140.apk / memory-freeform_1.0.140-debug.apk。
    applicationVariants.all {
        outputs.all {
            val variantName = this@all.name
            val suffix = if (variantName.equals("debug", ignoreCase = true)) "-debug" else ""
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                ?.outputFileName = "memory-freeform_${defaultConfig.versionName}${suffix}.apk"
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
        aidl = false
    }

    // ★ 1.0.102：Kotlin 2.x 起 Compose 编译器由 org.jetbrains.kotlin.plugin.compose 提供，
    //   不再需要 composeOptions.kotlinCompilerExtensionVersion。

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }


}

// ★ 1.0.102：Kotlin 2.x 的编译器选项 DSL（替代已废弃的 kotlinOptions 块）
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // ★ 1.0.102：compose 1.10 需要较新的 activity / lifecycle
    //   （lifecycle 2.11 要 compileSdk 37 + AGP 9.1，超出本工具链，取 2.10）
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // ★ 1.0.102：compose-bom 2024.09.03 -> 2026.03.01（foundation 1.10.6，miuix 0.8.x 要求 ≥1.10）
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    // 设置页出入栈的过渡动效（AnimatedContent / SizeTransform / tween）
    implementation("androidx.compose.animation:animation")

    // ★ 1.0.102：miuix 0.3.0 -> 0.8.8 —— 引入 WindowDialog（HyperOS 居中弹窗）。
    //   0.8.x 拆成了多个构件（ui/icons/shapes…），顶层 `miuix` 会一并带齐；
    //   它用 Kotlin 2.3.20 编译，本项目已同步升级 Kotlin（见根 build.gradle.kts）。
    implementation("top.yukonga.miuix.kmp:miuix:0.8.8")
    // ★ 0.8.x 把图标拆成独立构件（MiuixIcons.Back / Search 在这里），不再随主包传递
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.8.8")

    // LSPosed 增强：中性化澎湃 Freeform 图层的 0.70 缩放 + 让小窗出生即目标几何。
    // compileOnly —— 不打包进 APK，由 LSPosed 框架在运行时提供。
    compileOnly("de.robv.android.xposed:api:82")

}
