plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "xyz.anekol.silentwg"
    compileSdk = 35
    defaultConfig {
        applicationId = "xyz.anekol.silentwg"
        minSdk = 34
        targetSdk = 35
        versionCode = 4
        versionName = "1.4"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
