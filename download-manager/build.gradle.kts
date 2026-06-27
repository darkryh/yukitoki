import org.gradle.api.tasks.bundling.Jar

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    `maven-publish`
}

kotlin {
    // The platform code uses expect/actual objects (still flagged Beta); opt in so the
    // build output stays clean instead of repeating the KT-61573 warning per file.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm()

    androidLibrary {
        namespace = "com.ead.yukitoki.manager"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    iosArm64 {
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
        val iosMain by creating {
            dependsOn(commonMain.get())
        }
        iosArm64Main.get().dependsOn(iosMain)
        iosSimulatorArm64Main.get().dependsOn(iosMain)

        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.okio)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.okio.fakefilesystem)
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

// --- Publishing -----------------------------------------------------------------
// KMP registers a publication per target automatically. We attach a (placeholder)
// javadoc jar and POM metadata so the artifacts are Maven-Central shaped. Group and
// version are inherited from the root project (io.github.darkryh.yukitoki / 1.0.0).
// Signing/Sonatype credentials are intentionally left to the release environment.
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(javadocJar)
        pom {
            name.set("Yukitoki Download Manager")
            description.set("Kotlin Multiplatform download manager: streaming downloads with progress, parallel queue orchestration, pause/resume, retries, segmented range downloads, checksums and file safety.")
            url.set("https://github.com/darkryh/yukitoki")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("darkryh")
                    name.set("darkryh")
                }
            }
            scm {
                url.set("https://github.com/darkryh/yukitoki")
                connection.set("scm:git:https://github.com/darkryh/yukitoki.git")
                developerConnection.set("scm:git:ssh://git@github.com/darkryh/yukitoki.git")
            }
        }
    }
}
