// Not published. Real-socket tests of what an app relies on: HTTP/2, dropped connections, large files,
// full disks and real CDNs. They need the server in server/, so run them through run.sh.
// A plain `gradlew build` compiles them but doesn't run them.
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvmToolchain(21)
    jvm()
    iosSimulatorArm64()
    android {
        namespace = "io.github.darkryh.yukitoki.integration"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        // Test names with spaces need DEX 040, and nothing here ships.
        minSdk = 30
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(project(":download-manager"))
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidDeviceTest") {
            dependsOn(commonTest.get())
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation("androidx.test:runner:1.6.2")
            implementation("androidx.test.ext:junit:1.2.1")
        }
    }
}

val testsEnabled = providers.gradleProperty("integrationTests").isPresent

tasks.withType<Test>().configureEach {
    enabled = testsEnabled
    outputs.upToDateWhen { false }
    // Proves the large-file tests stream to disk rather than holding files in memory.
    maxHeapSize = "256m"
    providers.gradleProperty("integrationTests.trustStore").orNull?.let {
        systemProperty("javax.net.ssl.trustStore", it)
        systemProperty("javax.net.ssl.trustStorePassword", "changeit")
    }
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    enabled = testsEnabled
    outputs.upToDateWhen { false }
    device.set(providers.gradleProperty("integrationTests.iosDevice").orElse("iPhone 17 Pro"))
    // run.sh boots the simulator and trusts the test CA there, which a standalone run wouldn't see.
    standalone.set(false)
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
