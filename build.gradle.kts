buildscript {
    val buildCryptoVersion = providers.gradleProperty("buildCryptoVersion").get()
    val guavaVersion = providers.gradleProperty("guavaVersion").get()
    val buildJdomVersion = providers.gradleProperty("buildJdomVersion").get()
    val buildJose4jVersion = providers.gradleProperty("buildJose4jVersion").get()
    val buildHttpClientVersion = providers.gradleProperty("buildHttpClientVersion").get()
    val buildCommonsLangVersion = providers.gradleProperty("buildCommonsLangVersion").get()
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        constraints {
            add("classpath", "org.apache.commons:commons-lang3:$buildCommonsLangVersion") {
                because("Fix CVE-2025-48924 in AGP tooling")
            }
            add("classpath", "org.apache.httpcomponents:httpclient:$buildHttpClientVersion") {
                because("Fix CVE-2020-13956 in AGP tooling")
            }
            add("classpath", "org.bitbucket.b_c:jose4j:$buildJose4jVersion") {
                because("Fix CVE-2024-29371 in AGP tooling")
            }
            add("classpath", "org.jdom:jdom2:$buildJdomVersion") {
                because("Fix CVE-2021-33813 in AGP tooling")
            }
            add("classpath", "com.google.guava:guava:$guavaVersion-jre") {
                because("Fix CVE-2026-102554 in AGP tooling")
            }
            listOf("bcprov-jdk18on", "bcpkix-jdk18on", "bcutil-jdk18on").forEach { module ->
                add("classpath", "org.bouncycastle:$module:$buildCryptoVersion") {
                    because("Fix CVE-2026-8763 and CVE-2026-13506 in AGP tooling")
                }
            }
        }
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
    id("com.android.test") version "9.5.0" apply false
    id("androidx.baselineprofile") version "1.5.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

subprojects {
    val buildCryptoVersion = providers.gradleProperty("buildCryptoVersion").get()
    val guavaVersion = providers.gradleProperty("guavaVersion").get()
    val buildHttpClientVersion = providers.gradleProperty("buildHttpClientVersion").get()
    val buildCommonsLangVersion = providers.gradleProperty("buildCommonsLangVersion").get()
    configurations.configureEach {
        if (name == "androidLintTool") {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.apache.commons" && requested.name == "commons-lang3") {
                    useVersion(buildCommonsLangVersion)
                    because("Fix CVE-2025-48924 in Android Lint tooling")
                }
                if (requested.group == "org.apache.httpcomponents" && requested.name == "httpclient") {
                    useVersion(buildHttpClientVersion)
                    because("Fix CVE-2020-13956 in Android Lint tooling")
                }
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
}
