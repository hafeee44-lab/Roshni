import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Personal Solarman credentials can live in local.properties (which is gitignored) so a
 * developer's own build connects without going through onboarding. Nothing here is
 * required: when the keys are absent the app simply asks on first launch.
 */
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun local(key: String): String = (localProps.getProperty(key) ?: "").replace("\"", "")

android {
    namespace = "io.github.hafeee44lab.solargrid"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.hafeee44lab.solargrid"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SEED_APP_ID", "\"${local("solarman.appId")}\"")
        buildConfigField("String", "SEED_APP_SECRET", "\"${local("solarman.appSecret")}\"")
        buildConfigField("String", "SEED_EMAIL", "\"${local("solarman.email")}\"")
        buildConfigField("String", "SEED_PASSWORD_SHA256", "\"${local("solarman.passwordSha256")}\"")
        buildConfigField("String", "SEED_DEVICE_SN", "\"${local("solarman.deviceSn")}\"")
        buildConfigField("String", "SOURCE_URL", "\"https://github.com/hafeee44-lab/Roshni\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
