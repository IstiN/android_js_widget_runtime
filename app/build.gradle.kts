plugins {
    id("com.android.application")
}

android {
    namespace = "com.test.jsr"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.test.jsr"
        minSdk = 24
        targetSdk = 36
        externalNativeBuild {
            cmake {
                // armeabi-v7a — Tegra 2; arm64-v8a — современные планшеты
                arguments += listOf("-DANDROID_STL=none")
            }
        }
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}
