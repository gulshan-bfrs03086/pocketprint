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

/**
 * Bumping this to 37 turns local network access into a runtime permission, and
 * the failure without it is silent - printers stop being discovered and jobs to
 * a known address time out as though the printer were switched off. So the
 * build refuses to make that change without the permission being declared.
 */
val targetSdkVersion = 36

run {
    val manifest = file("src/main/AndroidManifest.xml").readText()
    val permission = "android.permission.ACCESS_LOCAL_NETWORK"
    require(targetSdkVersion < 37 || manifest.contains(permission)) {
        "targetSdk $targetSdkVersion needs $permission declared in the manifest, or " +
            "network discovery and printing stop working with no error anywhere. " +
            "Check the name against the API 37 SDK while you are here - it is " +
            "written out by hand in AppPermissions because it did not exist yet."
    }
}

android {
    namespace = "com.gulshan.pocketprint"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gulshan.pocketprint"
        targetSdk = targetSdkVersion
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
         * All it adds over modern is BLUETOOTH capped at API 30 - the single
         * permission a Bluetooth connection needed before API 31 split it into
         * BLUETOOTH_CONNECT and BLUETOOTH_SCAN. Install-time, prompts for
         * nothing.
         *
         * It used to carry ACCESS_FINE_LOCATION as well, because scanning below
         * API 31 required it, and aapt turned that into an implied
         * android.hardware.location feature - which is how this app once became
         * uninstallable on a rugged terminal with no GPS, reporting nothing
         * more useful than "Can't install the app". Scanning is the system
         * picker's job now, so the permission is gone and neither flavour asks
         * for location. The optional uses-feature declarations in the legacy
         * manifest stay behind as a guard against that returning unnoticed.
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
         * Identical to legacy except that it does not declare the pre-API-31
         * BLUETOOTH permission, which the platform stops honouring at API 31
         * regardless.
         *
         * That really is the whole difference, measured on the published v1.1.0
         * APKs: ten permissions against nine, and the tenth is the one above.
         * Both once differed on location access too, and no longer do. Whether
         * two builds still earn their keep for one install-time permission is
         * an open question rather than a settled design - see gulshan-hll2.
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
            // No v1. JAR signing is only consulted below API 24, and the lowest
            // flavour here starts exactly at 24, so apksigner omits it whatever
            // this config asks for. enableV1Signing used to be set here and the
            // APK came out without a v1 signature anyway.
            enableV2Signing = true
        }

        if (canSignRelease) {
            create("release") {
                storeFile = file(releaseStore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword

                // v2 is what carries the legacy flavour: it starts at Android
                // 7.0, which is the release that introduced v2 verification, so
                // every device it can install on can check it. v3 carries modern
                // by itself - apksigner stops emitting v2 from minSdk 28 up.
                //
                // No v1, and this is not an oversight to be corrected later.
                // Android consults a JAR signature only below API 24; nothing
                // built here goes that low. The flag was set for years with a
                // comment about OEM installers on "Android 7" needing it, which
                // was wrong twice over: API 24 *is* Android 7.0, and apksigner
                // reported v1: false on both v1.1.0 APKs while the flag was on.
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
    implementation(libs.androidx.core.splashscreen)
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
