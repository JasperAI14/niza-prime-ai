plugins {
    id("com.android.application")
}

android {
    namespace = "com.nizaprime.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nizaprime.ai"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9")
}
