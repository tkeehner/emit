import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val ksFile = rootProject.file("keystore.properties")
val ks = Properties().apply { if (ksFile.exists()) ksFile.inputStream().use { this.load(it) } }
fun ksVal(k: String, env: String): String? = ks.getProperty(k) ?: System.getenv(env)
val haveKs = ksVal("storeFile", "EMIT_STORE_FILE") != null
android {
    namespace = "com.example.emit"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.emit"
        minSdk = 29
        targetSdk = 35
        versionCode = 51
        versionName = "0.1.50"
    }
    // Release signing is config-driven: copy keystore.properties.example to keystore.properties (gitignored), or set
    // EMIT_STORE_FILE / EMIT_STORE_PASSWORD / EMIT_KEY_ALIAS / EMIT_KEY_PASSWORD. With neither, release uses the debug signing config.
    signingConfigs {
        if (haveKs) create("release") {
            storeFile = file(ksVal("storeFile", "EMIT_STORE_FILE")!!); storePassword = ksVal("storePassword", "EMIT_STORE_PASSWORD")
            keyAlias = ksVal("keyAlias", "EMIT_KEY_ALIAS"); keyPassword = ksVal("keyPassword", "EMIT_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true; isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (haveKs) "release" else "debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    // Dictionary files are already gzip; store them as-is.
    androidResources { noCompress += listOf("bin", "gz") }
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("org.json:json:20240303")
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
}
tasks.withType<Test>().configureEach { System.getProperty("shot.out")?.let { systemProperty("shot.out", it) } }
