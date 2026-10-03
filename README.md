# aot-cache-training

Record a [JVM AOT cache](https://openjdk.org/jeps/483) (JEP 483 / JEP 514) from your
Spring integration tests, and bundle it into a container image so your application starts
faster.

This library revives the proposal from
[spring-framework#36774](https://github.com/spring-projects/spring-framework/issues/36774)
(a feature request the Spring team
[declined](https://github.com/spring-projects/spring-framework/issues/36774#issuecomment-5508529440))
and maintains it as a standalone, community project.

## Why a standalone library

The Spring team's preference is the existing Buildpacks / Dockerfile training run. This
project is for teams who want to reuse their **integration-test workload** as the training
run, which is attractive because it already exercises realistic application code paths.

If that fits your workflow, this library gives you:

- a single switch to record a cache from your integration tests;
- automatic reuse of the cache in container images via the
  [Paketo Spring Boot buildpack](https://github.com/paketo-buildpacks/spring-boot/pull/609)
  (`aot-cache/application.aot`).

Like Quarkus (`@QuarkusIntegrationTest`), the training run boots your **packaged
application** in its own JVM and drives it with black-box HTTP tests. Recording against the
packaged application's own class path is the only way to produce a cache that application
can actually load, so it is the only mode this project supports.

## The JVM constraint you must know about

An AOT cache only loads against the **exact** JVM build, architecture and class path it was
recorded with. The class path of a packaged Spring Boot application (`runner.jar` plus
`lib/`) is **shorter** than a test class path, and the build host's JVM usually differs from
the runtime JVM. A cache recorded by an ordinary `@SpringBootTest` run therefore can never be
loaded by the packaged application.

This library sidesteps that by starting the packaged application in its own JVM with
`-XX:AOTCacheOutput`, so the recorded class path *is* the runtime class path. The integration
tests run as an external client and talk to the application over HTTP.

> Note: a cache also must be recorded and consumed with the same JVM distribution, OS,
> architecture, class path contents, and many JVM flags. The JVM rejects a cache that does
> not match, so mismatches cost the optimization rather than correctness. Run the training
> run and the application on the same JDK distribution.

## Modules

| Module | Purpose |
| --- | --- |
| `aot-cache-training` | `AotCache` helpers and `JUnitPlatformVersion` used by the build plugins. |
| `aot-cache-training-trainer` | `OutOfProcessTrainingLauncher`, the entry point that starts the packaged application and runs the tests as an HTTP client. |
| `aot-cache-training-maven-plugin` | Maven `record` and `verify` goals. |
| `aot-cache-training-gradle-plugin` | Gradle `aotCacheTraining` and `verifyAotCache` tasks. |

## Quick start

### Gradle

Kotlin DSL (`build.gradle.kts`):

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.github.vpelikh.aot-cache-training")
}

aotCacheTraining {
    enabled = true
    // readyUrl.set("http://localhost:8080/")     // default; polled until the app is ready
    // containerImage.set("my-app:latest")         // record inside this image's JVM
    // packagesToScan.set(listOf("com.example"))   // optional: limit the training workload
    // failOnTestFailure.set(true)                 // default
}
```

Groovy DSL (`build.gradle`):

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.1.1'
    id 'io.github.vpelikh.aot-cache-training'
}

aotCacheTraining {
    enabled = true
    // readyUrl = 'http://localhost:8080/'      // default
    // containerImage = 'my-app:latest'
    // packagesToScan = ['com.example']         // optional
    // failOnTestFailure = true                 // default
}
```

The training workload is your black-box integration tests. They read the running
application's base URL from the `aot.training.url` system property and drive it over HTTP:

```java
class GreetingHttpTests {

    @Test
    void greets() throws Exception {
        String baseUrl = System.getProperty("aot.training.url");
        // ... call the application over HTTP with java.net.http.HttpClient ...
    }

}
```

```bash
./gradlew aotCacheTraining verifyAotCache
```

This starts the packaged application (via `bootJar`), records
`build/aot-cache/application.aot` in the application's own JVM, and verifies it.

### Maven

The `record` goal needs the repackaged application JAR, so bind it to the `verify` phase
(after `package`):

```xml
<plugin>
    <groupId>io.github.vpelikh</groupId>
    <artifactId>aot-cache-training-maven-plugin</artifactId>
    <version>0.1.0</version>
    <executions>
        <execution>
            <id>aot-record</id>
            <phase>verify</phase>
            <goals><goal>record</goal></goals>
        </execution>
        <execution>
            <id>aot-verify</id>
            <goals><goal>verify</goal></goals>
        </execution>
    </executions>
</plugin>
```

```bash
mvn verify -Daot.cache.record=true
```

This records `target/aot-cache/application.aot`. The plugin locates the repackaged
application JAR automatically (or takes one with `applicationJar`).

No plugin-level dependencies are needed, and the plugin never modifies your test class
path. It derives your JUnit Platform version from the project and resolves a matching
launcher for its isolated client-test JVM, so a JUnit 5 and a JUnit 6 project both work
without any extra configuration.

### Using the cache in a container image

An AOT cache only loads against the **exact** JVM build, architecture and class path it was
recorded with. The build host's JVM usually differs from the image JRE, so record inside the
image to match it:

```kotlin
aotCacheTraining {
    enabled = true
    containerImage = "my-app:latest"
}
```

```bash
./gradlew aotCacheTraining bootBuildImage
```

The equivalent Maven configuration records inside the image:

```xml
<plugin>
    <groupId>io.github.vpelikh</groupId>
    <artifactId>aot-cache-training-maven-plugin</artifactId>
    <version>0.1.0</version>
    <executions>
        <execution>
            <id>aot-record</id>
            <phase>verify</phase>
            <goals><goal>record</goal></goals>
            <configuration>
                <!-- Record inside the image so the cache matches that image's JVM build. -->
                <containerImage>my-app:latest</containerImage>
            </configuration>
        </execution>
        <execution>
            <id>aot-verify</id>
            <goals><goal>verify</goal></goals>
        </execution>
    </executions>
</plugin>
```

```bash
mvn verify -Daot.cache.record=true
```

The Maven plugin locates the repackaged application JAR automatically (or takes one with
`applicationJar`).

The training launcher extracts the boot jar to `runner.jar` plus `lib/`, starts it with
`-XX:AOTCacheOutput=...` (optionally inside `containerImage`), waits for `readyUrl`, then
runs the tests as an external client. The tests read the running application's base URL from
the `aot.training.url` system property and call it over HTTP.

`verifyAotCache` then fails the build if no non-empty cache was produced. Ship the cache to
the buildpack by placing it at `aot-cache/application.aot` in the application content (for
example with `BP_INCLUDE_FILES='aot-cache/application.aot'`, or inside the packaged jar). The
Paketo Spring Boot buildpack detects it, skips its own training run, and loads it at startup
with `-XX:AOTCache=<path>`.

> The training workload must be black-box tests that call the application over HTTP. Plain
> `@SpringBootTest` tests run in-process and cannot drive the packaged application.

## How it works

1. **Package the application.** The plugin builds the Spring Boot boot JAR (`bootJar` for
   Gradle, `package` for Maven) so the application has a packaged class path.
2. **Run the training workload.** `OutOfProcessTrainingLauncher` extracts the boot JAR to
   `runner.jar` plus `lib/`, starts the application in its own JVM (optionally inside
   `containerImage`) with `-XX:AOTCacheOutput=<build>/aot-cache/application.aot`, waits for
   `readyUrl`, and runs your tests as an external HTTP client. The application's own class
   path is what gets recorded, so the cache matches what the image will load.
3. **Assemble the cache.** The application JVM assembles the final cache on clean exit,
   *after* shutdown hooks run.
4. **Verify.** The `verify` goal / task fails the build when recording was requested but no
   non-empty cache was produced. A training run that discovers no tests also fails by
   default, because an empty workload records a large but useless cache.

### JUnit Platform version alignment

The plugin resolves the test launcher at your project's own JUnit Platform version and
isolates it to the client-test JVM. The version is read from your resolved test dependencies
(falling back to JAR names). It never adds JUnit (or anything else) to your test class path,
so your dependency tree and your normal `test` task are untouched.

### Training JVM

The training run needs a JDK 25+ JVM. Gradle uses the project's Java toolchain (defaulting
to 25) for the training task; Maven uses the JVM running Maven. Both fail fast with an
actionable message when the training JVM is too old. For Maven, point the plugin at another
JVM with `-Daot.cache.trainingJvm=/path/to/java` or the `<trainingJvm>` configuration.

### Verification and shutdown hooks

The JVM assembles the cache **after** shutdown hooks have run. A shutdown-hook based check
would therefore always report a missing cache, so verification lives in the build tooling
and runs after the training JVM exits (`AotCache.verifyRecordedCache`).

## Requirements

- JDK 25+ to record (single-step `-XX:AOTCacheOutput`, JEP 514).
- JDK 24+ to load a pre-recorded cache (`-XX:AOTCache`, JEP 483).
- A packaged Spring Boot application (`bootJar` / `mvn package`) with an HTTP health or
  readiness endpoint the tests can poll, plus black-box tests that drive it over HTTP.
- Built against JUnit Platform 6 with JUnit Platform 5 runtime compatibility, and written
  for the JDK 25 toolchain the AOT cache feature requires.

## Building

```bash
./gradlew build
```

The Maven integration tests publish all modules to `build/local-repo` and run a real Maven
build against it.

## Releasing

Publishing is driven by the `release` workflow, run manually from the Actions tab
(**Run workflow**) with an optional version, defaulting to the one in `gradle.properties`. It:

1. builds and verifies the project,
2. publishes every module to Maven Central via the Sonatype Central Portal (the deployment is
   released automatically, no manual step),
3. publishes the Gradle plugin to the Gradle Plugin Portal,
4. creates the `vX.Y.Z` tag and a GitHub release. Its notes start from GitHub's
   pull-request list and then append any commits that were pushed directly, so a range that
   mixes pull requests and direct commits lists both, and
5. bumps the patch version in `gradle.properties` and pushes it, so the next release is ready.

The workflow reads these repository secrets:

| Secret | Purpose |
| --- | --- |
| `CENTRAL_PORTAL_USERNAME` / `CENTRAL_PORTAL_PASSWORD` | Sonatype Central Portal user token. |
| `SIGNING_KEY` | ASCII-armored GPG private key used to sign the Maven Central artifacts. |
| `SIGNING_PASSWORD` | Passphrase for `SIGNING_KEY`. |
| `GRADLE_PUBLISH_KEY` / `GRADLE_PUBLISH_SECRET` | Gradle Plugin Portal API key. |

## License

Apache License, Version 2.0.
