plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Sync README.md and the images it actually references into assets/help/ so the in-app help stays in sync.
// Only referenced images are packaged: readme_content/ also holds large files used elsewhere (store listing etc.).
val helpImages = Regex("readme_content/([A-Za-z0-9_.-]+)")
    .findAll(rootProject.file("README.md").readText()).map { it.groupValues[1] }.toSet()
val copyHelp = tasks.register<Sync>("copyHelpAssets") {
    from(rootProject.file("README.md"))
    from(rootProject.file("readme_content")) { include(helpImages); into("readme_content") }
    into(layout.projectDirectory.dir("src/main/assets/help"))
}
tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets")) ||
    it.name.startsWith("lintVitalAnalyze") ||
    it.name.startsWith("generateReleaseLintVitalReportModel")
}.configureEach {
    dependsOn(copyHelp)
}

android {
    namespace = "com.calmyjane.spacebeam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.calmyjane.spacebeam"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (System.getenv("KEYSTORE_PASSWORD") != null) {
        signingConfigs {
            create("release") {
                storeFile = file("release-keystore.jks")
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("KEYSTORE_PASSWORD") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Phones are ARM only; leaving out the x86 emulator builds of the native tracking libraries
            // shrinks the release APK considerably. Debug builds keep all ABIs so emulators still work.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    testOptions {
        // JVM unit tests: android.* stubs (Log etc.) return defaults instead of throwing
        unitTests.isReturnDefaultValues = true
    }
    androidResources {
        noCompress += "task"  // MediaPipe models (assets/*.task) are memory-mapped
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.remote.creation.core)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")  // real org.json for the JVM (android.jar only has stubs)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    val camerax_version = "1.3.0"
    implementation("androidx.camera:camera-core:$camerax_version")
    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation ("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")
    implementation("androidx.media3:media3-common:1.2.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}