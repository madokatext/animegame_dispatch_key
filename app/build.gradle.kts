plugins {
    id("com.android.application")
}

android {
    namespace = "local.dispatch.observer"
    compileSdk = 35

    defaultConfig {
        applicationId = "local.dispatch.observer"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "0.2-api100"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(project(":xposed-api"))
}
