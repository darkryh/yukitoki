package io.github.darkryh.yukitoki.buildlogic

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

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
        }
    }
}
