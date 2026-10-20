import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing. The keystore and its passwords never go into the repository: they're read from
// keystore.properties next to settings.gradle.kts (ignored by git), with storeFile, storePassword,
// keyAlias and keyPassword. Without that file, release builds come out unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.bitperfect.detector"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bitperfect.detector"
        // Bit-perfect (AudioMixerAttributes) exists since Android 14 (API 34).
        // With minSdk 34 the app can't be installed on earlier versions.
        minSdk = 34
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.4"

        // Report server (github.com/branches78/Bit-Perfect-Audio-Detector-Server) and website with the
        // phone table. Change them if you deploy your own server or use your own domain.
        buildConfigField("String", "REPORT_SERVER_URL", "\"https://bit-perfect-detector-server.onrender.com\"")
        buildConfigField("String", "WEBSITE_URL", "\"https://bit-perfect-detector-web.onrender.com\"")
    }

    signingConfigs {
        if (!keystoreProperties.isEmpty) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 removes unused code and resources, so the APK is much smaller.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
