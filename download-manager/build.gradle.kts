@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    id("io.github.darkryh.yukitoki.multiplatform-library")
    id("io.github.darkryh.yukitoki.maven-publish")
}

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    jvm()

    android {
        namespace = "io.github.darkryh.yukitoki.manager"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    iosArm64 {
        binaries.framework {
            isStatic = true
        }
    }

    iosX64 {
        binaries.framework {
            isStatic = true
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            isStatic = true
        }
    }

    wasmJs {
        browser()
    }

    sourceSets {
        val iosMain = create("iosMain") {
            dependsOn(named("commonMain").get())
        }
        iosArm64Main.get().dependsOn(iosMain)
        iosX64Main.get().dependsOn(iosMain)
        iosSimulatorArm64Main.get().dependsOn(iosMain)

        commonMain.dependencies {
            api(libs.ktor.client.core)
            api(libs.kotlinx.coroutines.core)
            api(libs.okio)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.okio.fakefilesystem)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.robolectric)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        jvmTest.dependencies {
            // Real end-to-end stack: a CIO client driving an embedded CIO server over
            // real sockets, writing through okio's real FileSystem.SYSTEM.
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
        }

        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}
