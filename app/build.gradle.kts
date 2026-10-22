plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val bandSdkEnabled = providers.gradleProperty("bandSdk").orNull == "true"
val signingEnvironment = listOf(
    "CLOCK_KEYSTORE_FILE",
    "CLOCK_KEYSTORE_PASSWORD",
    "CLOCK_KEY_ALIAS",
    "CLOCK_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val releaseSigningEnabled = signingEnvironment.values.all { !it.isNullOrBlank() }
require(signingEnvironment.values.all { it == null } || releaseSigningEnabled) {
    "Set all four CLOCK signing environment variables, or leave all unset for an unsigned build."
}

android {
    namespace = "com.akira.clock"
    compileSdk = 37

    if (releaseSigningEnabled) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(signingEnvironment.getValue("CLOCK_KEYSTORE_FILE")!!)
                storePassword = signingEnvironment.getValue("CLOCK_KEYSTORE_PASSWORD")
                keyAlias = signingEnvironment.getValue("CLOCK_KEY_ALIAS")
                keyPassword = signingEnvironment.getValue("CLOCK_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.akira.clock"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        viewBinding = false
        buildConfig = false
    }

    buildTypes {
        release {
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        create("bandPilot") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".bandpilot"
            versionNameSuffix = "-band-pilot"
            matchingFallbacks += listOf("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    sourceSets.getByName("main").kotlin.directories.add(
        if (bandSdkEnabled) "src/bandSdk/java" else "src/bandStub/java",
    )
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    if (bandSdkEnabled) {
        val sdk = file("libs/xms-wearable-lib_1.4_release.aar")
        require(sdk.exists()) { "Install the official Wear SDK: docs/band-control.md" }
        implementation(files(sdk))
    }
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
}
