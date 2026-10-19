plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rjkyker.shizukuai.broker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rjkyker.shizukuai.broker"
        minSdk = 28
        targetSdk = 34
        versionCode = 7
        versionName = "1.5.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    // Optional encrypted prefs (SecurePrefs falls back if unavailable at runtime)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Shizuku API — process-based ShizukuBridge works without these if resolution fails
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Dhizuku API: borrow Dhizuku's Device Owner after the user approves the broker in Dhizuku.
    // 2.5.4 = newest release that compiles against SDK 34 (2.6.0 needs compileSdk 37). Pure-Java AAR; any transitive kotlin-stdlib is excluded to stay on the project Kotlin 1.9 stdlib.
    implementation("io.github.iamr0s:Dhizuku-API:2.5.4") {
        exclude(group = "org.jetbrains.kotlin")
    }
    // Needed for Dhizuku's documented binder-wrapped DevicePolicyManager pattern (mService field).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    testImplementation("junit:junit:4.13.2")
}
