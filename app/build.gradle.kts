import java.net.URI

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
// MediaPipe hand + face landmark models (Apache 2.0, ~7.5 MB + ~3.6 MB) — fetched once into assets if missing
val trackingModels = listOf("hand_landmarker", "face_landmarker")
val trackingModelFiles = trackingModels.map { layout.projectDirectory.file("src/main/assets/$it.task").asFile }
val downloadHandModel = tasks.register("downloadHandModel") {
    outputs.files(trackingModelFiles)
    onlyIf { trackingModelFiles.any { !it.exists() } }
    doLast {
        trackingModels.zip(trackingModelFiles).forEach { (name, file) ->
            if (file.exists()) return@forEach
            file.parentFile.mkdirs()
            val url = "https://storage.googleapis.com/mediapipe-models/$name/$name/float16/1/$name.task"
            URI(url).toURL().openStream().use { input -> file.outputStream().use { out -> input.copyTo(out) } }
        }
    }
}
tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets")) ||
    it.name.startsWith("lintVitalAnalyze") ||
    it.name.startsWith("generateReleaseLintVitalReportModel")
}.configureEach {
    dependsOn(copyHelp)
    dependsOn(downloadHandModel)
}

android {
    namespace = "com.calmyjane.spacebeam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.calmyjane.spacebeam"
        minSdk = 29
        targetSdk = 35
        versionCode = 11
        versionName = "4.3"

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
    androidResources {
        noCompress += "task"  // MediaPipe model is memory-mapped from assets
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