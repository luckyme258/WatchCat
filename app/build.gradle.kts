// 版本口径见《安卓开发规范》W13：AGP 8.11.1 / Kotlin 2.1.0 / Gradle 8.13
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- 签名：本地与 CI 必须是同一份密钥 ----
// 本地：keystore/debug.keystore（已被 .gitignore 排除，不入公开仓库）
// CI  ：由 workflow 从 Secret 解出，通过 WATCHCAT_KEYSTORE 指向临时文件
// 两边不是同一份 → install -r 会报 INSTALL_FAILED_UPDATE_INCOMPATIBLE
val keystorePath: String = System.getenv("WATCHCAT_KEYSTORE") ?: "keystore/debug.keystore"
val keystoreFile = file(keystorePath)
val storePass: String = System.getenv("WATCHCAT_KEYSTORE_PASS") ?: "android"
val keyAliasName: String = System.getenv("WATCHCAT_KEY_ALIAS") ?: "androiddebugkey"
val keyPass: String = System.getenv("WATCHCAT_KEY_PASS") ?: "android"

android {
    namespace = "com.luckyme.watchcat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.luckyme.watchcat"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // 只编两个 ABI：真机 arm64 + 模拟器 x86_64（规范 W13）
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // 规范 W7：用到 BuildConfig 必须显式打开，否则报 Unresolved reference
    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("fixed") {
                storeFile = keystoreFile
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("fixed")?.let { signingConfig = it }
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfigs.findByName("fixed")?.let { signingConfig = it }
        }
    }

    // extractNativeLibs=false：`.so` 不压缩进 APK，由系统直接 mmap
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
