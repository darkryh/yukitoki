plugins {
    // Declared here so the plugins are resolved once for the build and applied
    // (without the version) in each module. Keeps the classloader single-instanced.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
}

group = providers.gradleProperty("yukitokiGroup").orElse("io.github.darkryh.yukitoki").get()
version = providers.gradleProperty("yukitokiVersion").orElse("1.0.0-SNAPSHOT").get()

subprojects {
    group = rootProject.group
    version = rootProject.version
}
