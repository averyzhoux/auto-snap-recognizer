plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.averyzhoux.recognizer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.averyzhoux.recognizer"
        minSdk = 24
        targetSdk = 37

        // ★ 版本号 / 版本名**只有这一个来源**：就在这里手写。
        //   CI 不再从 tag 推一套、也不再 -P 注入进来（以前两套值会对不上，见下）。
        //   发新版 = 改这两行 → 提交 → 打 tag。
        //
        //   versionCode：给系统判断新旧用，单调递增的整数，**不能被 tag 或别处覆盖**。
        //     以前这个值是 CI 从 tag 算出来注入的，而本地默认写死 3，两者差一个量级：
        //     装过 CI 版（300）之后再 install -r 本地版（3）会被 Android 拒
        //     （INSTALL_FAILED_VERSION_DOWNGRADE），唯一的补救是卸载重装 ——
        //     数据集和相册都在私有目录里，一卸就没。现在只有一个来源，不会再有这种事。
        //
        //   versionName：给人看的。系统的「应用信息」页显示的就是它，
        //     所以写有意义的版本名，不要写成一个数字。

        versionCode = 304
        versionName = "v0.3.4-Hephaestus"

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