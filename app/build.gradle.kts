plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

// Room writes its exported schema JSON here so database version bumps are
// reviewable in git instead of being a runtime surprise.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.nadi.health"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.nadi.health"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-O3", "-ffast-math", "-std=c++17")
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_ARM_NEON=ON")

            }
        }


        ndk {
            abiFilters += listOf("arm64-v8a")  // Target modern Arm chips only
        }

        // 16KB page alignment - required by Android 15/16 devices (iQOO 15 / Android 16)
        // and Google Play policy for apps containing native code.
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384")
            }
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"

        }
    }


    buildFeatures {
        compose = true      // Use Jetpack Compose for UI
        viewBinding = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.3"
    }


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // SNPE requires legacy (extracted) native packaging: the Hexagon DSP-side
        // loader (fastrpc) must read libSnpeHtpVxxSkel.so as real files from the
        // app's nativeLibraryDir - it cannot mmap them out of the APK zip. With
        // useLegacyPackaging=false the Skels never land on disk and SNPE fails
        // with error 73 ("None of the selected runtime targets are supported").
        jniLibs {
            useLegacyPackaging = true
            // GenieX ships the same SNPE/QNN HTA runtime the SNPE AAR does.
            // The AAR copies win (they are the matched set GenieX loads).
            pickFirsts += "lib/arm64-v8a/libhta_hexagon_runtime_snpe.so"
        }
    }
}

dependencies {
    implementation("androidx.compose.material:material-icons-extended:1.5.4")

    // Qualcomm SNPE runtime - iQOO 15-exclusive NPU execution. The app runs
    // inference ONLY on the Hexagon NPU (HTP) via a DLC model - there is NO
    // CPU/GPU fallback. Without this AAR the build succeeds but PulseML throws
    // Iqoo15NpuUnavailableException at startup and the UI shows a hard error.
    // Official release builds MUST ship it:
    //   cp $SNPE_ROOT/lib/android/snpe-release.aar app/libs/
    val snpeAar = file("libs/snpe-release.aar")
    if (snpeAar.exists()) {
        implementation(files(snpeAar))
    } else {
        logger.warn(
            "====================================================================\n" +
            "  app/libs/snpe-release.aar NOT FOUND - this is an iQOO 15-exclusive\n" +
            "  build (Hexagon NPU only, no fallbacks). Copy the AAR from the SNPE\n" +
            "  SDK before releasing: \$SNPE_ROOT/lib/android/snpe-release.aar\n" +
            "===================================================================="
        )
    }

    // Kotlin & Compose
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")

    // Camera & Vision
    implementation("androidx.camera:camera-camera2:1.3.0")
    implementation("androidx.camera:camera-lifecycle:1.3.0")
    implementation("androidx.camera:camera-view:1.3.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.0")

    // Math
    implementation("org.apache.commons:commons-math3:3.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")

    // --- Local storage (Room) ---
    // Health readings and workout sessions are persisted on-device so history
    // survives process death and app restarts.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // --- Health Connect ---
    // Reads heart rate / steps / distance / exercise sessions aggregated from
    // connected watches and phones, and writes this app's own measurements back.
    implementation(libs.androidx.health.connect)

    // GenieX SDK (official, in-process) — runs the qairt w4a16 bundle on the
    // Hexagon NPU from Kotlin. v0.7.0 AAR from qualcomm/GenieX releases;
    // bundles its own matched QNN/HTP runtime + v81 skeletons, so the old
    // manual jniLibs set and the ADSP_LIBRARY_PATH dance are gone for good.
    // It exposes com.geniex.sdk.{GenieXSdk,ModelManagerWrapper,LlmWrapper}.
    implementation(files("libs/geniex-android-aar-v0.7.0.aar"))

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Missing UI components (required for XML themes)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}