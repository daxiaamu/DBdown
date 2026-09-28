plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.daxiaamu.dbdown"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.daxiaamu.dbdown"
        minSdk = 33
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 18
        versionName = "0.6.9"
        buildConfigField("String", "UPDATE_REPOSITORY", "\"daxiaamu/DBdown\"")
        buildConfigField("String", "UPDATE_BRANCH", "\"main\"")
    }
    val signingPath = providers.environmentVariable("DBDOWN_KEYSTORE").orNull
    if(signingPath != null) signingConfigs.create("distribution") {
        storeFile = file(signingPath)
        storePassword = providers.environmentVariable("DBDOWN_STORE_PASSWORD").get()
        keyAlias = providers.environmentVariable("DBDOWN_KEY_ALIAS").get()
        keyPassword = providers.environmentVariable("DBDOWN_KEY_PASSWORD").get()
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if(signingPath != null) signingConfig = signingConfigs.getByName("distribution")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("dev.chrisbanes.haze:haze:1.7.3")
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.02.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-muxer:1.9.0")
    implementation("androidx.media3:media3-transformer:1.9.0")
    implementation("androidx.media3:media3-effect:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
}
