plugins {
    id("com.android.application")
}

android {
    namespace = "com.sampmobile.fork"
    compileSdk = 34

    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.sampmobile.fork"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // 32-bit only (armeabi-v7a): the engine libs we ship are 32-bit.
        ndk {
            abiFilters += listOf("armeabi-v7a")
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
