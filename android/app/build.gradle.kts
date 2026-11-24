import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Optional, in local.properties (never committed): waqi.key=...  Without it, Pulse uses the area estimate.
val waqiKey: String = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull
    ?.let { text -> Properties().apply { load(text.reader()) }.getProperty("waqi.key") }
    .orEmpty().filter { it.isLetterOrDigit() }

android {
    namespace = "com.fitbittrack.pulse"
    compileSdk = 37

    defaultConfig {
        // This ID is typed into Google Cloud when you create the Android OAuth client.
        applicationId = "com.fitbittrack.pulse"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "WAQI_KEY", "\"$waqiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Google sign-in (Google Identity Services authorization API)
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)

    // Calling the Google Health API
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Local database (offline copy of your health data)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Background sync
    implementation(libs.androidx.work.runtime.ktx)

    // Small settings store (goals, sync bookkeeping)
    implementation(libs.androidx.datastore.preferences)

    // Home-screen widgets (UI session)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Health Connect: today's numbers on this phone, without the internet trip
    implementation(libs.androidx.health.connect)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
