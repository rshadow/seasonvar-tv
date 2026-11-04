buildscript {
    val buildCryptoVersion = providers.gradleProperty("buildCryptoVersion").get()
    val guavaVersion = providers.gradleProperty("guavaVersion").get()
    val buildJdomVersion = providers.gradleProperty("buildJdomVersion").get()
    val buildJose4jVersion = providers.gradleProperty("buildJose4jVersion").get()
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        constraints {
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
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
