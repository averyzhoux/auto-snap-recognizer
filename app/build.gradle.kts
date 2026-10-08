plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// CI 从 tag 注入版本号（-PversionName=1.2.3 -PversionCode=10203）；
// 本地不传就用默认值，方便直接 Run。
val injectedVersionName = providers.gradleProperty("versionName").orNull
val injectedVersionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull()

android {
    namespace = "dev.averyzhoux.recognizer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.averyzhoux.recognizer"
        minSdk = 24
        targetSdk = 37
        versionCode = injectedVersionCode ?: 2
        versionName = injectedVersionName ?: "v0.2.1-Athena"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // 相机页底部要显示版本号，读的是 BuildConfig.VERSION_NAME / VERSION_CODE。
        // AGP 8 起 BuildConfig 默认不生成，所以要显式打开。
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ML Kit 文字识别（英文/拉丁文）
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}