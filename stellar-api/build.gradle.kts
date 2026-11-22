plugins {
    // 版本由根工程统一提供，这里只声明使用
    id("com.android.library")
}

/**
 * Stellar 特权 API 的客户端部分。
 *
 * 官方 JitPack 发布物缺少 aidl / shared / userservice 三个模块（api 的 pom 却依赖它们），
 * 无法直接作为二进制依赖引入，故按上游源码内联为本地模块。
 * 来源：github.com/roro2239/Stellar-API，GPL-3.0 / Apache-2.0（Shizuku 原始部分）。
 */
android {
    namespace = "roro.stellar.shared"
    compileSdk = 37

    defaultConfig {
        minSdk = 34
        buildConfigField("int", "SERVER_API_VERSION", "103")
        buildConfigField("String", "SERVER_API_VERSION_NAME", "\"1.0.3\"")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir("src/main/kotlin")
        }
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation(libs.androidx.core.ktx)
}
