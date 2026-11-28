import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val moduleProps = Properties().apply {
    val propFile = rootProject.file("../module.prop")
    if (propFile.isFile) {
        propFile.inputStream().use { load(it) }
    }
}
val resolvedVersionCode = moduleProps.getProperty("versionCode")?.trim()?.toIntOrNull() ?: 1
val resolvedVersionName = moduleProps.getProperty("version")
    ?.trim()
    ?.removePrefix("v")
    ?.substringBefore("-")
    ?.ifBlank { null }
    ?: "1.0.0"

android {
    namespace = "io.github.dr34m3rt.retailscaled"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.dr34m3rt.retailscaled"
        minSdk = 29
        targetSdk = 35
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
    val keyAliasValue = System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() }
    val keyPasswordValue = System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: keystorePassword
    val hasReleaseSigning = keystorePath != null &&
        keystorePassword != null &&
        keyAliasValue != null &&
        file(keystorePath).isFile

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
