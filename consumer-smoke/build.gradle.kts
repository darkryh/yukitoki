// Not published. Simulates a downstream JVM app that depends on Yukitoki the way a consumer
// does (runtime classpath only), then runs it with the jars in alphabetical and in reverse
// order. Packaged desktop apps (jpackage / Compose Desktop .app) list jars alphabetically,
// which is how issue #15 escaped every Gradle test: `HttpClient()` picks the first engine
// ServiceLoader finds, so the result must not depend on classpath order.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":download-manager"))
}

val smokeMain = "io.github.darkryh.yukitoki.smoke.ConsumerSmokeKt"

fun registerOrderedRun(taskName: String, reverse: Boolean) = tasks.register<JavaExec>(taskName) {
    group = "verification"
    description = "Runs the consumer smoke check with the runtime classpath sorted " +
        if (reverse) "in reverse alphabetical order." else "alphabetically (packaged-app order)."
    val runtime = sourceSets.main.get().runtimeClasspath
    // Wrapping in a provider drops the collection's build dependencies, so restore them.
    dependsOn(runtime)
    classpath = files(provider { runtime.files.sortedBy { it.name }.let { if (reverse) it.reversed() else it } })
    mainClass.set(smokeMain)
}

val alphabeticalRun = registerOrderedRun("consumerSmokeAlphabetical", reverse = false)
val reversedRun = registerOrderedRun("consumerSmokeReversed", reverse = true)

tasks.register("consumerSmoke") {
    group = "verification"
    description = "Runs the consumer smoke check under both classpath orders."
    dependsOn(alphabeticalRun, reversedRun)
}

tasks.named("check") { dependsOn("consumerSmoke") }
