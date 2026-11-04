import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// The release artifact stays arm64-v8a-only. The instrumentation job opts into
// x86_64 so the debug build can run on a GitHub-hosted emulator.
val includeX86Abi = providers.gradleProperty("includeX86Abi").orNull.toBoolean()

// The model is fetched rather than committed. Fail before packaging if it is
// absent or truncated, instead of shipping an app that cannot detect hazards.
val detectionModelFile = layout.projectDirectory
    .file("src/main/assets/models/hazard_efficientdet_lite0.tflite")
val verifyDetectionModel = tasks.register("verifyDetectionModel") {
    val modelFile = detectionModelFile.asFile
    doLast {
        check(modelFile.isFile) {
            "Detection model is missing. Run 'bash tools/fetch_model.sh' from the repository root."
        }
        val bytes = modelFile.length()
        check(bytes in 1_000_000..5_242_880) {
            "Detection model has unexpected size $bytes bytes. Re-run 'bash tools/fetch_model.sh'."
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyDetectionModel) }

android {
    namespace = "com.edgevision.wallpaper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.edgevision.wallpaper"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += if (includeX86Abi) {
                listOf("arm64-v8a", "x86_64")
            } else {
                listOf("arm64-v8a")
            }
        }
    }

    signingConfigs {
        // CI passes these variables after provisioning keystores with keytool.
        val releaseStorePath = System.getenv("EDGEVISION_RELEASE_STORE_FILE")
        if (!releaseStorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(releaseStorePath)
                storePassword = System.getenv("EDGEVISION_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("EDGEVISION_RELEASE_KEY_ALIAS") ?: "edgevision"
                keyPassword = System.getenv("EDGEVISION_RELEASE_KEY_PASSWORD")
            }
        }

        val debugStorePath = System.getenv("EDGEVISION_DEBUG_STORE_FILE")
        if (!debugStorePath.isNullOrBlank()) {
            create("ciDebug") {
                storeFile = file(debugStorePath)
                storePassword = System.getenv("EDGEVISION_DEBUG_STORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("EDGEVISION_DEBUG_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("EDGEVISION_DEBUG_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            signingConfig = signingConfigs.findByName("ciDebug")
                ?: signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            // Only the release keystore signs a release build. Falling back to the
            // debug key produces an APK that no store build can upgrade, and does it
            // silently, so the opt-in below is required for a local debug-signed build.
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
        }
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }

    applicationVariants.all {
        val variantName = name
        outputs.all {
            // Emit the exact artifact names required by the pipeline.
            (this as? BaseVariantOutputImpl)?.outputFileName = "app-arm64-v8a-$variantName.apk"
        }
    }
}

// Shipping an APK called "release" that is signed with the debug key is a silent
// trap: it installs fine, but no store build can upgrade it. Building one now has
// to be asked for explicitly. The check lives in its own task, and the values it
// needs are read inside that task so the configuration cache can serialize it.
val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    val keystoreConfigured = System.getenv("EDGEVISION_RELEASE_STORE_FILE").isNullOrBlank().not()
    val debugSigningAllowed = providers.gradleProperty("allowDebugReleaseSigning")
        .orNull
        .toBoolean()
    doLast {
        if (!keystoreConfigured && !debugSigningAllowed) {
            throw GradleException(
                "assembleRelease needs a release keystore: set EDGEVISION_RELEASE_STORE_FILE, " +
                    "EDGEVISION_RELEASE_STORE_PASSWORD, EDGEVISION_RELEASE_KEY_ALIAS and " +
                    "EDGEVISION_RELEASE_KEY_PASSWORD, or pass " +
                    "-PallowDebugReleaseSigning=true for a locally installable " +
                    "debug-signed build.",
            )
        }
    }
}

// Fails a release build before it compiles anything.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

chaquopy {
    defaultConfig {
        version = "3.12"
        buildPython("python3")
        // The detector has no third-party Python dependencies. tflite-runtime is
        // used automatically if a compatible Android wheel is present; otherwise
        // the Python pipeline drives the bundled TensorFlow Lite interpreter.
        pip {}
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test.espresso:espresso-core:3.6.1")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
