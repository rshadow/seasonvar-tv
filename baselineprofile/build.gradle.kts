plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "ru.seasonvar.tv.baselineprofile"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

kotlin { jvmToolchain(17) }

baselineProfile { useConnectedDevices = true }

dependencies {
    constraints {
        implementation("com.squareup.wire:wire-runtime:${providers.gradleProperty("benchmarkWireVersion").get()}") {
            because("Fix CVE-2026-63126 in benchmark trace parsing")
        }
    }
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.1")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
