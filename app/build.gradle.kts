plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.couchreader"
    compileSdk = 35
    defaultConfig {
        applicationId = if (providers.gradleProperty("isolatedTestApp").isPresent) "dev.couchreader.qa" else "dev.couchreader"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
