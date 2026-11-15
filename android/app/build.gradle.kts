import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// local.properties 에 넣는 값 (커밋하지 않음)
//   crew.baseUrl=http://10.0.2.2:8000      ← 에뮬레이터에서 PC 의 백엔드
//   pleos.clientId=...                     ← Pleos Playground My Project 에서 발급
//   pleos.clientSecret=...
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String, default: String = "") = localProps.getProperty(key) ?: default

android {
    namespace = "com.whatsthesauce"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.whatsthesauce"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "CREW_BASE_URL", "\"${localProp("crew.baseUrl", "http://10.0.2.2:8000")}\"")
        buildConfigField("String", "PLEOS_CLIENT_ID", "\"${localProp("pleos.clientId")}\"")
        buildConfigField("String", "PLEOS_CLIENT_SECRET", "\"${localProp("pleos.clientSecret")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // CarUxRestrictionsManager (주행 중 UX 제한)
    useLibrary("android.car")

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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.media)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.bundles.pleos.sdk)
}
