package io.github.darkryh.yukitoki.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class MultiplatformLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            group = findProperty("yukitokiGroup") as? String
                ?: rootProject.group.toString().takeUnless { it == "unspecified" }
                ?: "io.github.darkryh.yukitoki"
            version = findProperty("yukitokiVersion") as? String
                ?: rootProject.version.toString().takeUnless { it == "unspecified" }
                ?: "1.0.0-SNAPSHOT"

            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            pluginManager.apply("com.android.kotlin.multiplatform.library")

            extensions.configure<KotlinMultiplatformExtension> {
                jvmToolchain(JavaLanguageVersion.of(21).asInt())
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }
}
