# Publishing Yukitoki

Yukitoki publishes the `download-manager` Kotlin Multiplatform artifact under:

```text
io.github.darkryh.yukitoki:download-manager:<version>
```

The Gradle convention plugins live in `build-logic`:

- `io.github.darkryh.yukitoki.multiplatform-library` configures shared KMP library defaults.
- `io.github.darkryh.yukitoki.maven-publish` configures Maven Central publishing, signing, POM metadata, and local publication validation.

## Version

The default version is declared in the root `build.gradle.kts`.

For release builds, override it without editing source:

```bash
./gradlew validatePublishing -PyukitokiVersion=1.0.1
```

## Local Validation

Run the full build:

```bash
./gradlew check
```

Validate Maven metadata, signing setup, and all local publications:

```bash
./gradlew validatePublishing
```

Publish only to Maven local:

```bash
./gradlew :download-manager:publishToMavenLocal
```

## Maven Central

The build uses Vanniktech Maven Publish and publishes through the Maven Central Portal.
Credentials and signing material are read from the standard Vanniktech/Gradle publishing environment on the machine or CI runner.

Publish and automatically release:

```bash
./gradlew :download-manager:publishAndReleaseToMavenCentral
```

Use `publishToMavenCentral` instead if you want to stage/review before release.
