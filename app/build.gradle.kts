import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

android {
    namespace = "ru.seasonvar.app"
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
        versionCode = 19
        versionName = providers.gradleProperty("seasonvarVersionName").get()
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

    lint { checkDependencies = true }

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
    implementation(project(":ui-tv"))
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.1")
    androidTestImplementation("com.squareup.okhttp3:okhttp-tls:5.5.1")
    testImplementation("org.json:json:20260814")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    testImplementation(project(":core"))
    testImplementation("org.jsoup:jsoup:1.23.2")
    testImplementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.5.1")
    testImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    testImplementation("androidx.compose.foundation:foundation")
    testImplementation("androidx.media3:media3-datasource:1.11.1")
    testImplementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")

    androidTestImplementation(project(":core"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.activity:activity-compose:1.13.0")
    androidTestImplementation("androidx.compose.foundation:foundation")
    androidTestImplementation("androidx.tv:tv-material:1.1.0")
    androidTestImplementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    androidTestImplementation("io.coil-kt:coil-compose:2.7.0")
    androidTestImplementation("androidx.media3:media3-ui:1.11.1")
    androidTestImplementation("org.jsoup:jsoup:1.23.2")
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
    beforeVariants(selector().all()) { variant ->
        if (variant.buildType in setOf("nonMinifiedProfile", "benchmarkProfile")) variant.enable = false
    }
}

// Keep the existing cross-module probes on the assembled app without
// exposing implementation details through the production module APIs.
val testLibraryRoots = listOf(":core", ":ui-tv").map { project(it).projectDir.toPath() }
tasks.withType<KotlinCompile>().configureEach {
    if (name.endsWith("UnitTestKotlin") || name.endsWith("AndroidTestKotlin")) {
        friendPaths.from(libraries.filter { jar -> testLibraryRoots.any(jar.toPath()::startsWith) })
    }
}
