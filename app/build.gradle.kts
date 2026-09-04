import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * The version, declared once.
 *
 * Bumped on the release branch that carries the version, never on main
 * directly, so main always reads as the latest version that has landed.
 * See docs/RELEASING.md.
 */
val versionMajor = 1
val versionMinor = 1
val versionPatch = 0

/**
 * Version codes must be distinct integers that only increase, and the two
 * flavours are separate packages of the same version, so they cannot share
 * one. Derive the code from the version rather than tracking it by hand, and
 * give each flavour a low digit: modern sits above legacy so a device that can
 * install either takes the build with fewer permissions.
 */
val baseVersionCode = (versionMajor * 10000 + versionMinor * 100 + versionPatch) * 10

/**
 * Release signing material, when there is any.
 *
 * Read from keystore.properties at the repository root - which is gitignored,
 * and holds a path to a keystore that is also never committed - or from the
 * environment, so CI can pass it in from secrets without a file on disk.
 *
 * When neither is present the release build is left unsigned. It deliberately
 * does not fall back to the debug key: an APK signed with the public AOSP debug
 * key looks signed and is not, whereas one AGP names "-unsigned" tells the
 * truth. Being unsigned also keeps the release build compiling on a fork or a
 * pull request, where no secret is available and none should be.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingSetting(property: String, environment: String): String? =
    (keystoreProperties.getProperty(property) ?: System.getenv(environment))
        ?.takeIf { it.isNotBlank() }

val releaseStore = signingSetting("storeFile", "POCKETPRINT_KEYSTORE")
val releaseStorePassword = signingSetting("storePassword", "POCKETPRINT_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingSetting("keyAlias", "POCKETPRINT_KEY_ALIAS")
val releaseKeyPassword = signingSetting("keyPassword", "POCKETPRINT_KEY_PASSWORD")

val canSignRelease = listOf(
    releaseStore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword,
).all { it != null }

android {
    namespace = "com.gulshan.pocketprint"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gulshan.pocketprint"
        targetSdk = 36
        versionCode = baseVersionCode
        versionName = "$versionMajor.$versionMinor.$versionPatch"
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
            versionCode = baseVersionCode + 1
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
            versionCode = baseVersionCode + 2
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

        if (canSignRelease) {
            create("release") {
                storeFile = file(releaseStore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword

                // v1 for the same reason as debug: the legacy flavour installs
                // back to Android 7, and some OEM installers of that era only
                // look at the JAR signature.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
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

            // Null when no key is configured, which leaves an APK named
            // "-unsigned" rather than one signed by a key anyone else also has.
            signingConfig = signingConfigs.findByName("release")
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
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}
