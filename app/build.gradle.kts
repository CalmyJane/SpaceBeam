plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Copy README.md and readme_content/ into assets/help/ so the in-app help stays in sync
val copyHelp = tasks.register<Copy>("copyHelpAssets") {
    from(rootProject.file("README.md"))
    from(rootProject.file("readme_content"))  { into("readme_content") }
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
        versionCode = 10
        versionName = "4.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}