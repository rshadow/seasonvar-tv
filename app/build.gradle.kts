plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildCryptoVersion = providers.gradleProperty("buildCryptoVersion").get()
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
        versionCode = 2
        versionName = "0.3.0"
        testInstrumentationRunner = "ru.seasonvar.tv.probe.AccountMarkProbe"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
        create("profile") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            proguardFiles("profile-rules.pro")
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

configurations.configureEach {
    if (name == "androidLintTool") {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.bouncycastle" && requested.name in
                setOf("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on")) {
                useVersion(buildCryptoVersion)
                because("Fix CVE-2026-8763 and CVE-2026-13506 in Android Lint tooling")
            }
            if (requested.group == "com.google.guava" && requested.name == "guava") {
                useVersion("$guavaVersion-jre")
                because("Fix CVE-2026-102554 in Android Lint tooling")
            }
        }
    }
}
