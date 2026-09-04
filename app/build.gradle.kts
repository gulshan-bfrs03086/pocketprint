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
 * The version code, derived from the version rather than tracked by hand.
 *
 * The `* 10` is load-bearing, and is the one piece of the old two-flavour
 * layout that has to outlive it. There used to be two packages sharing one
 * applicationId, so each needed its own code: legacy took base + 1 and modern
 * base + 2, which is why v1.1.0 shipped 101001 and 101002.
 *
 * Version codes may only ever increase. Dropping the multiplier now that one
 * build is enough would take 1.2.0 down to 10200, an order of magnitude below
 * what is already installed. The require() below refuses to build in that case
 * rather than shipping an APK nobody can install.
 */
val baseVersionCode = (versionMajor * 10000 + versionMinor * 100 + versionPatch) * 10

/**
 * The highest code ever published: v1.1.0 modern. Legacy was 101001.
 *
 * Recorded because it is the floor every future build has to clear, and
 * nothing else in the tree remembers it.
 */
val highestPublishedVersionCode = 101002

/**
 * The single build takes the next free slot above the flavours it replaces.
 *
 * Not `baseVersionCode` alone, which would be 101000 at this version - below
 * both codes already installed on people's devices, so an APK built from main
 * would be refused as a downgrade by anyone running 1.1.0. The `+ 3` is simply
 * the first slot after legacy's `+ 1` and modern's `+ 2`; every later version
 * clears the floor on its own because the base grows.
 */
val appVersionCode = baseVersionCode + 3

require(appVersionCode > highestPublishedVersionCode) {
    "versionCode $appVersionCode is not above $highestPublishedVersionCode, which " +
        "is already published. Android refuses an update whose code does not " +
        "increase, and the only way out for a user is uninstalling - which takes " +
        "their configured printers with it. Most likely cause: the * 10 was " +
        "dropped from baseVersionCode after the flavours went away."
}

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

        // Android 7.0, which is where the old legacy flavour started. One build
        // now covers the whole range: the split existed to spare Android 12+
        // devices a permission, and there is no longer one to spare them.
        minSdk = 24
        targetSdk = targetSdkVersion
        versionCode = appVersionCode
        versionName = "$versionMajor.$versionMinor.$versionPatch"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // No v1. JAR signing is only consulted below API 24, and minSdk is
            // exactly 24, so apksigner omits it whatever this config asks for.
            // enableV1Signing used to be set here and the APK came out without
            // a v1 signature anyway.
            enableV2Signing = true
        }

        if (canSignRelease) {
            create("release") {
                storeFile = file(releaseStore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword

                // minSdk 24 is Android 7.0, the release that introduced v2
                // verification, so every device this can install on can check a
                // v2 signature. v3 rides along for Android 9 and up.
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
            // the trade runs the other way: uncompressed took the build from
            // 19 MB to 60 MB for the same code, measured on what was then the
            // modern variant. Revisit this if the app ever ships through Play.
            //
            // At minSdk 24 AGP compresses anyway, so today this changes nothing
            // and is a guard rather than an override. It starts mattering again
            // the moment minSdk reaches 28 - which is the point of leaving it
            // here rather than removing a line that currently does nothing.
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
