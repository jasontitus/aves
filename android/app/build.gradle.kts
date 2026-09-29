import com.android.build.gradle.internal.api.ApkVariantOutputImpl
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val packageName = "deckers.thibault.aves"

// Keys

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    println("Load keystore props from file=$keystorePropertiesFile")
    // for release using credentials stored in a local file
    keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
} else {
    println("Load keystore props from system environment")
    // for release using credentials in environment variables set up by GitHub Actions
    // warning: in property file, single quotes should be escaped with a backslash,
    // but they should not be escaped when stored in env variables
    val env = System.getenv()
    fun getEnv(propKey: String, envKey: String) {
        if (envKey in env) {
            keystoreProperties[propKey] = env[envKey]
        }
    }
    getEnv("storeFile", "AVES_STORE_FILE")
    getEnv("storePassword", "AVES_STORE_PASSWORD")
    getEnv("keyAlias", "AVES_KEY_ALIAS")
    getEnv("keyPassword", "AVES_KEY_PASSWORD")
    getEnv("googleApiKey", "AVES_GOOGLE_API_KEY")
}

// Preview credentials are never used for the official play, izzy, or libre identities.
val previewKeystoreProperties = Properties()
val previewKeystorePropertiesFile = System.getenv("AVES_PREVIEW_KEY_PROPERTIES")?.let { file(it) }
if (previewKeystorePropertiesFile != null) {
    check(previewKeystorePropertiesFile.isFile) {
        "Preview signing properties file not found: $previewKeystorePropertiesFile"
    }
    previewKeystorePropertiesFile.inputStream().use { previewKeystoreProperties.load(it) }
    check(listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .all { (previewKeystoreProperties[it] as? String)?.isNotBlank() == true }) {
        "Preview signing properties must contain a keystore path, passwords, and alias"
    }
    check(!(previewKeystoreProperties["keyAlias"] as String).equals("androiddebugkey", ignoreCase = true) &&
        File(previewKeystoreProperties["storeFile"] as String).name != "debug.keystore") {
        "Preview release must use a dedicated key, not the Android SDK debug key"
    }
}

android {
    namespace = "deckers.thibault.aves"
    compileSdk = 37
    ndkVersion = flutter.ndkVersion

    compileOptions {
        // enable support for the new language APIs on older devices
        // e.g. `java/util/function/Supplier` on Android 5.0 (API 21)
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        // Gradle looks up toolchain JDKs (for this app and each of its modules)
        // among locally installed JDKs (including in `~/.gradle/jdks/` and `~/jdks`)
        // and download them from configured repositories if necessary.
        jvmToolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
            vendor.set(JvmVendorSpec.ADOPTIUM) // Temurin
            // for consistency, refresh `gradle-daemon-jvm.properties` with the following:
            // ./gradlew updateDaemonJvm --jvm-version=21 --jvm-vendor=ADOPTIUM
        }
    }

    defaultConfig {
        applicationId = packageName
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        manifestPlaceholders["googleApiKey"] = keystoreProperties["googleApiKey"] ?: "NONE"
        multiDexEnabled = true
    }

    // remove dependency metadata from build artifacts
    // cf https://izzyondroid.org/about/security/ApkScans/#signingblock-checks
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        val storeFilePath = keystoreProperties["storeFile"] as String?
        if (storeFilePath != null) {
            println("Create signing config for release using file=$storeFilePath")
            create("release") {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = file(storeFilePath)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
        if (previewKeystorePropertiesFile != null) {
            create("previewRelease") {
                keyAlias = previewKeystoreProperties["keyAlias"] as String
                keyPassword = previewKeystoreProperties["keyPassword"] as String
                storeFile = file(previewKeystoreProperties["storeFile"] as String)
                storePassword = previewKeystoreProperties["storePassword"] as String
            }
        }
    }

    flavorDimensions += "store"

    productFlavors {
        create("play") {
            // Google Play
            dimension = "store"
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }

        create("izzy") {
            // IzzyOnDroid
            // check offending libraries with `scanapk`
            // cf https://android.izzysoft.de/articles/named/app-modules-2
            dimension = "store"
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }

        create("libre") {
            // F-Droid
            // check offending libraries with `fdroidserver`
            // cf https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/
            dimension = "store"
            applicationIdSuffix = ".libre"
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }

        create("preview") {
            // Side-by-side, libre-dependency fork builds; never replace upstream Aves Libre.
            dimension = "store"
            applicationIdSuffix = ".preview"
            signingConfigs.findByName("previewRelease")?.let { signingConfig = it }
            manifestPlaceholders["googleApiKey"] = "NONE"
        }
    }

    testOptions {
        // tests use JUnit Jupiter annotations
        unitTests.all { it.useJUnitPlatform() }
        // e.g. `android.util.Log` calls in code under test
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }

        getByName("profile") {
            applicationIdSuffix = ".profile"
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            // NDK ABI filters are incompatible with split APK generation,
            // but filters are necessary to exclude x86 native libs from universal APKs
            // cf https://github.com/flutter/flutter/issues/37566#issuecomment-640879500
            var useNdkAbiFilters = true
            if (rootProject.extra.has("split-per-abi")) {
                val splitPerAbi = rootProject.extra["split-per-abi"]
                if (splitPerAbi == "true" || splitPerAbi == true) {
                    useNdkAbiFilters = false
                }
            }
            if (useNdkAbiFilters) {
                ndk {
                    //noinspection ChromeOsAbiSupport
                    abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
                }
            }
        }

        val abiCodes = mapOf(
            "armeabi-v7a" to 1,
            "arm64-v8a" to 2,
            "x86" to 3,
            "x86_64" to 4
        )

        applicationVariants.all {
            println("Application variant applicationId=$applicationId name=$name")

            resValue("string", "screen_saver_settings_activity", "${applicationId}/${packageName}.ScreenSaverSettingsActivity")
            resValue("string", "search_provider", "${applicationId}.search_provider")

            outputs.forEach { output ->
                val abi = output.filters.find { it.filterType == "ABI" }?.identifier
                val baseAbiVersionCode = abiCodes[abi]
                if (baseAbiVersionCode != null) {
                    val versionCodeOverride = versionCode * 100 + baseAbiVersionCode
                    println("  output versionCodeOverride=$versionCodeOverride for abi=$abi")
                    (output as ApkVariantOutputImpl).versionCodeOverride = versionCodeOverride
                }
            }
        }
    }
}

androidComponents {
    // With no dedicated key, the preview release variant does not exist, even for
    // aggregate or abbreviated Gradle tasks. Debug/profile variants remain local-only.
    beforeVariants(selector().withFlavor("store", "preview").withBuildType("release")) { variant ->
        if (previewKeystorePropertiesFile == null) variant.enable = false
    }
    onVariants(selector().withFlavor("store", "izzy")) { variant ->
        // uncompressed native libraries are recommended:
        // https://developer.android.com/build/releases/agp-4-2-0-release-notes#compress-native-libs-dsl
        // but compressed native libraries yield smaller APK files, following Izzy's policy
        variant.packaging.jniLibs.useLegacyPackaging = true
    }
}

flutter {
    source = "../.."
}

repositories {
    maven {
        url = uri("https://jitpack.io")
        content {
            includeGroup("com.github.deckerst")
            includeGroup("com.github.deckerst.mp4parser")
        }
    }
    maven {
        url = uri("https://s3.amazonaws.com/repo.commonsware.com")
        content {
            excludeGroupByRegex("com\\.github\\.deckerst.*")
        }
    }
}

dependencies {
    // cf https://developer.android.com/studio/write/java8-support#library-desugaring
    coreLibraryDesugaring(libs.android.desugarJdkLibs)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.ktx)
    implementation(libs.androidx.performance)
    implementation(libs.androidx.lifecycle)
    implementation(libs.androidx.media)
    // Jetpack `security-crypto` library is deprecated:
    // https://developer.android.com/privacy-and-security/cryptography#security-crypto-jetpack-deprecated
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime)

    implementation(libs.commonsware.cwac)
    implementation(libs.metadata.extractor)
    // on-device inference for smart search
    implementation(libs.onnxruntime.android)
    implementation(libs.glide)
    implementation(libs.google.material)
    // SLF4J implementation for `mp4parser`
    implementation(libs.slf4j)

    // forked, built by JitPack:
    // - https://jitpack.io/p/deckerst/Android-TiffBitmapFactory
    // - https://jitpack.io/p/deckerst/androidsvg
    // - https://jitpack.io/p/deckerst/mp4parser
    // - https://jitpack.io/p/deckerst/pixymeta-android
    implementation(libs.deckerst.tiffbitmapfactory)
    implementation(libs.deckerst.androidsvg)
    implementation(libs.deckerst.mp4parser.isoparser)
    implementation(libs.deckerst.mp4parser.muxer)
    implementation(libs.deckerst.pixymeta)
    implementation(project(":exifinterface"))

    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.platform.launcher)

    ksp(libs.glideKsp)

    compileOnly(rootProject.findProject(":streams_channel")!!)
}

if (rootProject.extra["aves_useCrashlytics"] as Boolean) {
    println("Building flavor with Crashlytics plugin")
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
} else {
    println("Building flavor without reporting plugin")
}
