package io.github.darkryh.yukitoki.buildlogic

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

class MavenPublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.vanniktech.maven.publish")

            extensions.configure<MavenPublishBaseExtension> {
                publishToMavenCentral(automaticRelease = true)
                signAllPublications()

                coordinates(
                    groupId = group.toString(),
                    artifactId = name,
                    version = version.toString(),
                )

                pom {
                    name.set("Yukitoki Download Manager")
                    description.set(
                        "Kotlin Multiplatform download manager with streaming downloads, progress, pause/resume, retries, segmented range downloads, checksums, and file safety.",
                    )
                    url.set("https://github.com/darkryh/yukitoki")

                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }

                    scm {
                        url.set("https://github.com/darkryh/yukitoki")
                        connection.set("scm:git:https://github.com/darkryh/yukitoki.git")
                        developerConnection.set("scm:git:ssh://git@github.com/darkryh/yukitoki.git")
                    }

                    developers {
                        developer {
                            id.set("darkryh")
                            name.set("Xavier Alexander Torres Calderon")
                            email.set("alex_torres-xc@hotmail.com")
                            organization.set("Darkryh")
                            organizationUrl.set("https://github.com/darkryh")
                        }
                    }
                }
            }

            val validateMavenLocalPublication = tasks.register<ValidateMavenLocalPublicationTask>("validateMavenLocalPublication") {
                group = "publishing"
                description = "Publishes this module to Maven local and verifies every publication directory has a POM."
                dependsOn("publishToMavenLocal")
                groupId.set(project.group.toString())
                versionName.set(project.version.toString())
                mavenLocal.fileValue(project.gradle.gradleUserHomeDir.resolve("../.m2/repository").canonicalFile)
            }

            afterEvaluate {
                val publishing = extensions.getByType(PublishingExtension::class.java)
                validateMavenLocalPublication.configure {
                    artifactIds.set(publishing.publications.withType(MavenPublication::class.java).map { it.artifactId })
                }
            }
        }
    }
}
