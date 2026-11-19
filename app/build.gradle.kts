plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

val guavaVersion = providers.gradleProperty("guavaVersion").get()

android {
    namespace = "ru.seasonvar.tv"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "ru.seasonvar.tv"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "1.1.3"
        testInstrumentationRunner = "ru.seasonvar.tv.probe.AccountMarkProbe"
    }

    buildTypes {
        getByName("release") {
            versionNameSuffix = providers.gradleProperty("versionNameSuffix").orNull
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        create("profile") {
            initWith(getByName("release"))
            val isolatedCpuProbe = providers.gradleProperty("isolatedCpuProbe").orNull == "true"
            if (isolatedCpuProbe) applicationIdSuffix = ".cpucheck"
            manifestPlaceholders["profileAppLabel"] = if (isolatedCpuProbe) "Seasonvar CPU probe" else "@string/app_name"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            proguardFiles("profile-rules.pro")
        }
        create("nonMinifiedRelease") {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".baseline"
        }
        create("benchmarkRelease") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".baseline"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    baselineProfile(project(":baselineprofile"))
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    constraints {
        implementation("com.google.guava:guava:$guavaVersion-android") {
            because("Fix CVE-2026-102554 in Media3 transitive dependencies")
        }
    }
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.tv:tv-material:1.1.0")

    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.5.1")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.1")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:5.5.1")
    testImplementation("org.json:json:20260814")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

baselineProfile {
    mergeIntoMain = true
    automaticGenerationDuringBuild = false
    saveInSrc = true
    warnings { disabledVariants = false }
}

androidComponents {
    finalizeDsl { extension ->
        // The profile plugin replaces generated variants' source sets with release sources.
        for (name in listOf("nonMinifiedRelease", "benchmarkRelease")) {
            extension.sourceSets.getByName(name).apply {
                manifest.srcFile("src/nonMinifiedRelease/AndroidManifest.xml")
            }
        }
    }
    onVariants(selector().all()) { variant ->
        if (variant.buildType in setOf("nonMinifiedRelease", "benchmarkRelease")) {
            variant.sources.kotlin?.addStaticSourceDirectory("src/nonMinifiedRelease/java")
        }
    }
    beforeVariants(selector().all()) { variant ->
        if (variant.buildType in setOf("nonMinifiedProfile", "benchmarkProfile")) variant.enable = false
    }
}
