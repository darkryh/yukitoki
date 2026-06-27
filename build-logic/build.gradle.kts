import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(21)
}

val libs = the<LibrariesForLibs>()

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.vanniktech.maven.publish)
}

gradlePlugin {
    plugins {
        register("multiplatformLibrary") {
            id = "io.github.darkryh.yukitoki.multiplatform-library"
            implementationClass = "io.github.darkryh.yukitoki.buildlogic.MultiplatformLibraryConventionPlugin"
        }
        register("mavenPublish") {
            id = "io.github.darkryh.yukitoki.maven-publish"
            implementationClass = "io.github.darkryh.yukitoki.buildlogic.MavenPublishConventionPlugin"
        }
    }
}
