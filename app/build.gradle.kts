import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Load version properties
val versionPropsFile = project.rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { load(it) }
    }
}

// version.properties is the single source of truth for the version. The build
// reads it verbatim and never mutates it — bumping the version is a deliberate
// edit to that file, not a side effect of compiling. (Previously the build
// auto-incremented versionBuild/versionPatch and wrote them back, so the APK
// version was always the file value + 1 and the committed file drifted on
// every local build.)
// Properties.load() strips leading but not trailing whitespace, so trim each
// value — a stray trailing space in a hand-edited file would otherwise crash
// versionBuild.toInt() or produce a malformed versionName like "1.0.5 ".
val verMajor = versionProps.getProperty("versionMajor", "1").trim()
val verMinor = versionProps.getProperty("versionMinor", "0").trim()
val verPatch = versionProps.getProperty("versionPatch", "0").trim()
val verBuild = versionProps.getProperty("versionBuild", "1").trim().toInt()
val currentVersionName = "$verMajor.$verMinor.$verPatch"

kotlin {
    jvmToolchain(21)
}

// Hilt/Dagger's annotation processor reads Kotlin class metadata via kotlin-metadata-jvm.
// The version Dagger bundles trails the compiler, so once the project moved to Kotlin 2.4.0 the
// aggregating `hiltJavaCompile*` task failed with "Provided Metadata instance has version 2.4.0,
// while maximum supported version is 2.3.0". Force the parser to match the Kotlin we compile with
// so it can read the metadata we emit. Kept in lockstep with the catalog's `kotlin` version.
configurations.configureEach {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-metadata-jvm:${libs.versions.kotlin.get()}")
    }
}

android {
    namespace = "com.hereliesaz.cleanunderwear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hereliesaz.cleanunderwear"
        minSdk = 26
        targetSdk = 37
        versionCode = verBuild
        versionName = currentVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }

        // GH_TOKEN is only injected into debug builds (see buildTypes.debug below).
        // Release builds get an empty token so the GitHub crash reporter no-ops
        // instead of shipping the token inside the APK, where anyone with the
        // binary could extract it.
        buildConfigField("String", "GH_TOKEN", "\"\"")
    }

    buildTypes {
        debug {
            val localProperties = Properties()
            val localPropertiesFile = project.rootProject.file("local.properties")
            if (localPropertiesFile.exists()) {
                localProperties.load(localPropertiesFile.inputStream())
            }
            val ghToken = localProperties.getProperty("GH_TOKEN", "")
            buildConfigField("String", "GH_TOKEN", "\"$ghToken\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    sourceSets {
        getByName("test") {
            // Surface main/assets on the unit-test classpath so JVM tests can
            // load sources.json via getResourceAsStream("/sources.json")
            // without reaching outside the module via a hardcoded File path.
            resources.srcDirs("src/main/assets")
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val version = variant.outputs.first().versionName.get()
            val code = variant.outputs.first().versionCode.get()
            val apkName = "CleanUnderwear-${variant.name}-$version.$code.apk"
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(apkName)
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.navigation.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Jsoup
    implementation(libs.jsoup)

    // Icons
    implementation(libs.androidx.compose.material.icons.extended)

    debugImplementation(libs.androidx.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Image Loading
    implementation(libs.coil.compose)

    // On-Device AI
    implementation(libs.litert)
    implementation(libs.litert.tensorflow.ops)

    // Thumb-Driven Navigation
    implementation(libs.aznavrail)

    // Paging
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
}
