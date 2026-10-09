plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.byso.yahoomailsearch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.byso.yahoomailsearch"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "2.0.3"

        // Optional non-secret email prefill. Never embed Yahoo app passwords in an APK.
        val initialEmail = (System.getenv("YAHOO_EMAIL") ?: "")
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "")
            .replace("\r", "")
        buildConfigField("String", "DEFAULT_YAHOO_EMAIL", "\"$initialEmail\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/NOTICE.md",
            "META-INF/LICENSE.md",
            "META-INF/NOTICE",
            "META-INF/LICENSE"
        )
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("com.android.billingclient:billing-ktx:9.1.0")
    implementation("com.sun.mail:android-mail:1.6.8")
    implementation("com.sun.mail:android-activation:1.6.8")
}
