plugins {
    id("com.android.application")
}

android {
    namespace = "com.test.jsr"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.test.jsr"
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                // armeabi-v7a — Tegra 2; arm64-v8a — современные планшеты
                arguments += listOf("-DANDROID_STL=none")
            }
        }
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // android.util.Log и прочие заглушки android.jar молчат в JVM-тестах.
            isReturnDefaultValues = true
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildTypes {
        debug {
            // createDebugUnitTestCoverageReport (JaCoCo XML) для crap4kotlin.
            isTestCoverageEnabled = true
        }
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

dependencies {
    // Секреты: EncryptedSharedPreferences (issue #3)
    implementation("androidx.security:security-crypto:1.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("androidx.test:core:1.7.0")
    // Настоящий org.json вместо заглушек android.jar в JVM-тестах.
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

// План 509: фикстуры пина js_widget_runtime -> androidTest assets
// (генерируемый каталог; статический srcDir androidTest упаковывает их
// в androidTest APK). connectedDebugAndroidTest тянет задачу сам.
val conformanceFixtures = tasks.register<Copy>("conformanceFixtures") {
    val pin = rootProject.file(".conformance/js_widget_runtime/docs/conformance/fixtures")
    doFirst { require(pin.isDirectory) { "нет фикстур пина: $pin — запустите scripts/fetch-conformance.sh" } }
    from(pin)
    into(rootProject.file("app/src/androidTest/assets/conformance"))
}
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach { dependsOn(conformanceFixtures) }

// connectedConformance: результат инструментальных тестов -> reports/conformance.
tasks.register<Copy>("connectedConformance") {
    dependsOn("connectedDebugAndroidTest")
    from(layout.buildDirectory.dir("outputs/connected_android_test_additional_output")) {
        include("**/conformance/*.result.json")
    }
    into(layout.buildDirectory.dir("reports/conformance"))
}
