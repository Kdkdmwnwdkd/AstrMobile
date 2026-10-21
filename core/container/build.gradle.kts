plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.astrmobile.core.container"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation(project(":core:common"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("org.tukaani:xz:1.9")
    // NapCat 只给二维码内容（登录链接）不给图片，得本地画码
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    // 单元测试跑在 JVM 上，android.jar 里的 org.json 只是空壳，得用真实实现
    testImplementation("org.json:json:20260814")
}
