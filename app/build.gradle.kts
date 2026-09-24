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
        versionCode = 10
        versionName = "2.2-MD3"
    }
    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "silentwg123"
            keyAlias = "silentwg"
            keyPassword = "silentwg123"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
