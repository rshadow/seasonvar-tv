plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseVersion = providers.gradleProperty("seasonvarVersionName").get()
val releaseSuffix = providers.gradleProperty("versionNameSuffix").getOrElse("")
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
        minSdk = 26
        buildConfigField("String", "VERSION_NAME", "\"$releaseVersion\"")
    }
    buildTypes {
        getByName("release") {
            buildConfigField("String", "VERSION_NAME", "\"$releaseVersion$releaseSuffix\"")
        }
        create("profile") {
            initWith(getByName("release"))
        }
        create("nonMinifiedRelease")
        create("benchmarkRelease") {
            initWith(getByName("release"))
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        if (variant.buildType in setOf("nonMinifiedRelease", "benchmarkRelease")) {
            variant.sources.kotlin?.addStaticSourceDirectory("src/nonMinifiedRelease/java")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    constraints {
        implementation("androidx.profileinstaller:profileinstaller:1.4.1")
        api("com.google.guava:guava:$guavaVersion-android") {
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
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
