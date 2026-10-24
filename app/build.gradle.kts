plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.lite.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.lite.launcher"
        minSdk = 21
        targetSdk = 34
        // CI passes these from the release tag (vX.Y.Z -> X.Y.Z, X*10000+Y*100+Z) so every release outranks the last.
        versionCode = providers.gradleProperty("liteVersionCode").orNull?.toInt() ?: 10100
        versionName = providers.gradleProperty("liteVersionName").orNull ?: "1.1.0"
    }

    // Release key comes from ~/.gradle/gradle.properties; without it, release builds use the debug key.
    val storeFile = providers.gradleProperty("liteStoreFile").orNull
    signingConfigs {
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = providers.gradleProperty("liteStorePassword").get()
                keyAlias = providers.gradleProperty("liteKeyAlias").get()
                keyPassword = providers.gradleProperty("liteKeyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (storeFile != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}
