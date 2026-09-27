plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.msj.gfx"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.msj.gfx"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        create("release") {
            // Supply your own keystore via ~/.gradle/gradle.properties, never commit it.
            val storePath = providers.gradleProperty("MSJ_STORE_FILE").orNull
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("MSJ_STORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("MSJ_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("MSJ_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (providers.gradleProperty("MSJ_STORE_FILE").isPresent)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }

    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json"
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

/**
 * Writes the release APK out again under its product name.
 *
 * assembleRelease produces app-release.apk, and everything downstream of that
 * wants a different name: the GitHub release asset, the download, and the file
 * a user drops on a phone. Doing it here rather than in a shell step means a
 * local `./gradlew assembleRelease` leaves the same artefact in
 * app/build/outputs/apk/release/, so the name is not something that only
 * exists in CI.
 *
 * The name contains a space, which GitHub's release-asset upload rewrites to a
 * dot - assets come back as "M.S.J.GFX.apk" no matter what is named here. The
 * file inside the uploaded artifact keeps the exact name, and the copy on disk
 * keeps it too.
 */
val namedReleaseApk by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Copies the release APK out as M.S.J GFX.apk"
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        rename { "M.S.J GFX.apk" }
    }
    into(layout.buildDirectory.dir("outputs/apk/named"))
}

tasks.named("assembleRelease") { finalizedBy(namedReleaseApk) }
