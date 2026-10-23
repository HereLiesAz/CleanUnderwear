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

// The build reads version.properties and never writes it. The release workflows bump it
// after each release and pass the released name and code as -P properties (below); a local
// build gets major.minor.patch from the file.
// Properties.load() strips leading but not trailing whitespace, so trim each
// value — a stray trailing space in a hand-edited file would otherwise crash
// versionBuild.toInt() or produce a malformed versionName like "1.0.5 ".
val verMajor = versionProps.getProperty("versionMajor", "1").trim()
val verMinor = versionProps.getProperty("versionMinor", "0").trim()
val verPatch = versionProps.getProperty("versionPatch", "0").trim()
val verBuild = versionProps.getProperty("versionBuild", "1").trim().toInt()
val currentVersionName = "$verMajor.$verMinor.$verPatch"
// The central release workflows (HereLiesAz/workflows) rewrite version.properties
// on the runner and also pass -PversionCodeOverride / -PversionName; honour those
// when present so the uploaded build carries exactly the code Play expects.
val releaseVersionCode = (findProperty("versionCodeOverride") as String?)?.trim()?.toIntOrNull() ?: verBuild
val releaseVersionName = (findProperty("versionName") as String?)?.trim()?.takeIf { it.isNotEmpty() } ?: currentVersionName

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

// Room writes each schema version here (committed), so migrations can be tested against the
// exact shape every released version had.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.hereliesaz.cleanunderwear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hereliesaz.cleanunderwear"
        minSdk = 26
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
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
        unitTests.isIncludeAndroidResources = true
        // Robolectric downloads its Android jars from Maven Central; a mirror can be named
        // for networks where Central is throttled.
        unitTests.all { test ->
            System.getenv("ROBOLECTRIC_REPO")?.let { test.systemProperty("robolectric.dependency.repo.url", it) }
        }
    }
    sourceSets {
        getByName("test") {
            // Exported Room schemas, for MigrationTestHelper.
            assets.srcDirs("$projectDir/schemas")
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
    // Room on the JVM: migrations against the exported schemas, and the DAO's real SQL.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)

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
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // In-app update check for Play installs (UpdateChecker)
    implementation(libs.play.app.update.ktx)

    // On-Device AI
    implementation(libs.litert)
    implementation(libs.litert.tensorflow.ops)

    // Thumb-Driven Navigation
    implementation(libs.aznavrail)

    // Paging
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
}
