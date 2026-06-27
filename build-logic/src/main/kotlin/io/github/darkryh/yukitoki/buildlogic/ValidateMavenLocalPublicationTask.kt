package io.github.darkryh.yukitoki.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction

abstract class ValidateMavenLocalPublicationTask : DefaultTask() {
    @get:Input
    abstract val groupId: Property<String>

    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val artifactIds: ListProperty<String>

    @get:InputDirectory
    abstract val mavenLocal: DirectoryProperty

    @TaskAction
    fun validate() {
        val groupPath = groupId.get().replace('.', '/')
        val version = versionName.get()
        val repository = mavenLocal.get().asFile

        artifactIds.get().forEach { artifactId ->
            val publicationDir = repository.resolve("$groupPath/$artifactId/$version")
            val pomFile = publicationDir.resolve("$artifactId-$version.pom")
            check(pomFile.isFile) {
                "Expected POM for artifact '$artifactId' at ${pomFile.absolutePath}"
            }
        }
    }
}
