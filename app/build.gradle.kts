plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.astrmobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.astrmobile"
        minSdk = 26
        targetSdk = 34
        versionCode = 42
        versionName = "0.16.2"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        // 固定 debug 签名：CI 每次都用同一套密钥，新版本才能覆盖安装而不丢数据。
        // 这是公开的调试密钥，不可用于正式发布。
        create("fixedDebug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixedDebug")
        }
        release {
            isMinifyEnabled = false
            // 正式包同样使用固定密钥，避免 assembleRelease 产出未签名 APK
            signingConfig = signingConfigs.getByName("fixedDebug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        jniLibs {
            // 必须保留传统打包方式，proot/busybox 等被重命名的 .so 需要在
            // nativeLibraryDir 中保持可执行权限：容器引擎依赖这一点。
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }

    androidResources {
        // rootfs 压缩包与原生库不能再被二次压缩，否则解压耗时并可能损坏。
        noCompress += setOf("xz", "tar", "so")
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:container"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
