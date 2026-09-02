plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.gulshan.pocketprint"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gulshan.pocketprint"
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * Two distributions of the same app.
     *
     * They are not a feature split: both build the identical code and print the
     * identical bytes. The difference is entirely in what the package asks the
     * system for, which is what decides whether it installs and what it prompts
     * for on a given device.
     */
    flavorDimensions += "reach"
    productFlavors {
        /**
         * Android 7.0 and up, for older and rugged hardware.
         *
         * Carries the pre-API-31 Bluetooth permissions, which drag in
         * ACCESS_FINE_LOCATION, which in turn forces the location hardware
         * features to be declared optional or the package will not install on a
         * device without GPS.
         */
        create("legacy") {
            dimension = "reach"
            minSdk = 24
            versionNameSuffix = "-legacy"
        }

        /**
         * Android 12 and up.
         *
         * Asks for no location permission at all: BLUETOOTH_SCAN with
         * neverForLocation covers discovery from API 31. Fewer permission
         * prompts, no location access, and it cannot hit the required-feature
         * install failure by construction.
         */
        create("modern") {
            dimension = "reach"
            minSdk = 31
            versionNameSuffix = "-modern"
        }
    }

    signingConfigs {
        getByName("debug") {
            // AGP drops JAR signing when minSdk >= 24. Android 9 verifies v2
            // fine, but some OEM package installers on older releases still
            // want v1, and including it costs nothing on a sideloaded build.
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // Required so java.time / try-with-resources style APIs work back on API 24.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")

        dex {
            // From minSdk 28 AGP stores DEX uncompressed so ART can mmap it:
            // faster installs and lower memory, paid for in file size. That is
            // the right trade when Play ships an AAB and compresses in transit.
            //
            // This app is sideloaded - APKs are copied to devices by hand - so
            // the trade runs the other way: uncompressed took the modern
            // variant from 19 MB to 60 MB for the same code. Revisit this if
            // the app ever ships through Play.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
