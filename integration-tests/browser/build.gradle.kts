// Not published. The page integration-tests/browser/run.mjs drives in real browsers.
@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    wasmJs {
        outputModuleName.set("browser")
        browser()
        binaries.executable()
    }
    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":download-manager"))
            implementation(libs.kotlinx.browser)
        }
    }
}
