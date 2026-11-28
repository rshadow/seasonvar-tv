plugins {
    id("com.android.library")
}

val releaseVersion = providers.gradleProperty("seasonvarVersionName").get()
val releaseSuffix = providers.gradleProperty("versionNameSuffix").getOrElse("")

android {
    namespace = "ru.seasonvar.core"
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
    buildFeatures.buildConfig = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    api("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("androidx.core:core:1.18.0")
    constraints {
        // Use the app's resolved versions when Lint analyzes this library on its own.
        implementation("androidx.collection:collection-jvm:1.5.0")
        implementation("androidx.concurrent:concurrent-futures:1.2.0")
        implementation("androidx.lifecycle:lifecycle-runtime:2.9.4")
        implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    }
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:5.5.1")
    implementation("org.jsoup:jsoup:1.23.2")
}
