plugins {
    // Declared here so the plugins are resolved once for the build and applied
    // (without the version) in each module. Keeps the classloader single-instanced.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

group = providers.gradleProperty("yukitokiGroup").orElse("io.github.darkryh.yukitoki").get()
version = providers.gradleProperty("yukitokiVersion").orElse("1.0.0").get()

subprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.register("validatePublishing") {
    group = "publishing"
    description = "Validates Maven Central metadata, signing configuration, and Maven local publication for the published library."
    dependsOn(
        ":download-manager:checkPomFileForKotlinMultiplatformPublication",
        ":download-manager:checkPomFileForJvmPublication",
        ":download-manager:checkPomFileForAndroidPublication",
        ":download-manager:checkPomFileForIosArm64Publication",
        ":download-manager:checkPomFileForIosSimulatorArm64Publication",
        ":download-manager:checkPomFileForWasmJsPublication",
        ":download-manager:checkSigningConfiguration",
        ":download-manager:validateMavenLocalPublication",
    )
}
