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

// ---- ABI（规范 W13 / L8）：产物只出 arm64，模拟器调试显式加回来 ----
// 默认只打 arm64-v8a：CI 与 Release 拿到的是同一个口径，不会出现「发布包里混进一份 x86_64」。
// 本机跑模拟器要验对齐时才加：gradle -PwatchcatDevAbi=x86_64 :app:assembleDebug
val devAbis: List<String> = (project.findProperty("watchcatDevAbi") as String?)
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?: emptyList()
val packagedAbis: List<String> = listOf("arm64-v8a") + devAbis

android {
    namespace = "com.luckyme.watchcat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.luckyme.watchcat"
        minSdk = 30
        targetSdk = 36
        versionCode = 7
        versionName = "0.5.2"

        // 只打 packagedAbis（默认 arm64-v8a），见文件顶部说明
        ndk {
            abiFilters += packagedAbis
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
            // hook 侧关混淆（L7）：入口类名必须与 META-INF/xposed/java_init.list 一致
            proguardFiles("proguard-rules.pro")
            signingConfigs.findByName("fixed")?.let { signingConfig = it }
        }
    }

    // extractNativeLibs=false：`.so` 不压缩进 APK，由系统直接 mmap
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        // modern LSP 模块描述走 META-INF/xposed/，必须原样进 APK（L2）
        resources {
            merges += "META-INF/xposed/*"
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

dependencies {
    // L1：基线 API 101，不追 102。API 只在编译期可见，运行时由框架注入。
    // 这两个 AAR 的 POM 声明了 kotlin-stdlib 2.2.10（源码本身是纯 Java），
    // 与本项目的 Kotlin 2.1.0 冲突，所以排除；不排除会把 stdlib 顶到 2.2.10。
    compileOnly("io.github.libxposed:api:101.0.0") {
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation("io.github.libxposed:service:101.0.0") {
        exclude(group = "org.jetbrains.kotlin")
    }

    // 判定层单测（L16.2.1 的替代品）：纯 JVM、CI 里跑，不碰 Android 类型
    testImplementation("junit:junit:4.13.2")
}
