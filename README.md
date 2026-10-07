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
application** in its own JVM (optionally inside a target container image) and drives it with
black-box HTTP tests. Recording against the packaged application's own class path is the only
way to produce a cache that application can load, so it is the only training mode this
project provides.

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
| `aot-cache-training-integration-tests` | End-to-end tests: build each example's image and verify the recorded AOT cache loads. |

## Quick start

### Gradle

The minimal setup is a single switch. The plugin builds your boot JAR, starts it in its own
JVM, drives it with your tests, and records `build/aot-cache/application.aot`.

Kotlin DSL (`build.gradle.kts`):

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.github.vpelikh.aot-cache-training")
}

aotCacheTraining {
    enabled = true
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
}
```

### Maven

The same single switch, plus the two goals. `record` needs the repackaged application JAR,
so it binds to the `verify` phase (after `package`) by default:

```xml
<plugin>
    <groupId>io.github.vpelikh</groupId>
    <artifactId>aot-cache-training-maven-plugin</artifactId>
    <version>0.1.0</version>
    <executions>
        <execution>
            <id>aot-record</id>
            <goals><goal>record</goal></goals>
        </execution>
        <execution>
            <id>aot-verify</id>
            <goals><goal>verify</goal></goals>
        </execution>
    </executions>
</plugin>
```

This records `target/aot-cache/application.aot`. The plugin locates the repackaged
application JAR automatically (or takes one with `applicationJar`).

`record` is off by default; enable it with `-Daot.cache.record=true` or `<enabled>true</enabled>`.
The optional settings below apply here too, set through `<configuration>` (for example
`<containerImage>`). No plugin-level dependencies are needed, and the plugin never modifies
your test class path.

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

Run the training:

```bash
./gradlew aotCacheTraining verifyAotCache     # Gradle
mvn verify -Daot.cache.record=true            # Maven
```

### Optional settings

Both build tools share the same optional settings; the ones you are most likely to touch:

| Setting | Default | Purpose |
| --- | --- | --- |
| `readyUrl` | `http://localhost:8080/` | URL polled until the application is ready. |
| `containerImage` | unset | Record inside this image's JVM instead of the local one. Only needed when the runtime image's JVM build or architecture differs from your build host. |
| `jvmArguments` | unset | Extra JVM options for the recording JVM, for example `--enable-native-access=ALL-UNNAMED`. An AOT cache only loads when the runtime JVM uses the same options it was recorded with. |
| `packagesToScan` | unset | Limit the training workload to specific packages. |
| `failOnTestFailure` | `true` | Fail the training run when a test fails. |
| `allowEmptyWorkload` | `false` | Record even when no tests are discovered. |
| `startTimeout` | `120` | Seconds to wait for the application to become ready. |

### Using the cache in a container image

`containerImage` is optional. Leave it unset when the runtime JVM matches your build host:
the packaged application then records against the local JVM, and the cache loads wherever
that same JVM build and architecture run.

Set it when the cache must be loaded by an image whose JVM differs from the build host, a
different JDK distribution or version, or a different CPU architecture. This is the common
case: building on macOS arm64 and deploying a `linux/amd64` image produces a cache the image
cannot load. Recording inside the image fixes it, because an AOT cache only loads against the
**exact** JVM build, architecture and class path it was recorded with:

```kotlin
aotCacheTraining {
    enabled = true
    containerImage = "my-app:latest"   // the image the cache must match
}
```

```bash
./gradlew aotCacheTraining
```

With Maven, add the same setting to the `record` goal's configuration:

```xml
<configuration>
    <!-- Record inside the image so the cache matches that image's JVM build. -->
    <containerImage>my-app:latest</containerImage>
</configuration>
```

Then build the image. Both plugins wire this up for you with no manual `jar` step: they
embed the recorded `aot-cache/application.aot` into the packaged application JAR, where the
buildpack looks for it.

```bash
./gradlew bootBuildImage                                   # Gradle
mvn verify spring-boot:build-image-no-fork -Daot.cache.record=true   # Maven
```

The Paketo Spring Boot buildpack finds `aot-cache/application.aot` in the application
content, skips its own training run, and loads the cache at startup with `-XX:AOTCache`.

- Gradle points `bootBuildImage` at a cache-embedded copy of the boot JAR automatically.
- Maven embeds the cache during the `record` goal into both the repackaged application JAR and
  its `target/<finalName>.jar.original` backup, because `spring-boot:build-image` re-lays-out the
  application from that backup (`<embedInApplicationJar>false</embedInApplicationJar>` to opt
  out). Use `build-image-no-fork`, not `build-image`: the forking `build-image` goal reruns
  `package` and would overwrite the embedded JAR.

> Pre-recorded cache support needs Paketo Spring Boot buildpack **5.39.0 or later**, and the
> `bootBuildImage` builder must bundle it.

> The training workload must be black-box tests that call the application over HTTP. Plain
> `@SpringBootTest` tests run in-process and cannot drive the packaged application.

#### Without a buildpack (plain Dockerfile)

If you build your image with a plain `docker build` instead of `bootBuildImage`, copy the
recorded cache into the image yourself. Two details decide whether the cache loads:

- **Flatten the boot JAR** to the layout the cache was recorded against: extract it with
  `java -Djarmode=tools -jar app.jar extract`, rename the application JAR to `runner.jar`,
  and start the JVM with `-cp runner.jar` (its manifest `Class-Path` supplies `lib/`). A cache
  does **not** load from the original fat-JAR class path.
- **Normalize the extracted file timestamps**, for example
  `find . -name '*.jar' -exec touch -d @315532801 {} +`. The JVM rejects a cache whose
  class-path entries have changed timestamps.

```dockerfile
FROM eclipse-temurin:25

RUN adduser --system --home /app --disabled-password --disabled-login app-user
WORKDIR /app

COPY build/libs/ /app/libs/
COPY build/aot-cache/application.aot /app/aot-cache/application.aot

RUN set -eux; \
    boot_jar="$(find /app/libs -name '*.jar' ! -name '*-plain.jar' | head -n1)"; \
    java -Djarmode=tools -jar "$boot_jar" extract --destination /app/extracted; \
    mv /app/extracted/*.jar /app/runner.jar; \
    mv /app/extracted/lib /app/lib; \
    rmdir /app/extracted; \
    rm -rf /app/libs; \
    find /app -name '*.jar' -exec touch -d @315532801 {} +; \
    touch -d @315532801 /app/aot-cache/application.aot; \
    chown -R app-user /app

USER app-user
ENTRYPOINT ["java", "-XX:AOTCache=/app/aot-cache/application.aot", "-cp", "/app/runner.jar", "com.example.Application"]
```

Set `containerImage` to the same base image so the recorded cache matches the runtime JVM:

```kotlin
aotCacheTraining {
    enabled = true
    containerImage = "eclipse-temurin:25"
}
```

```bash
./gradlew aotCacheTraining verifyAotCache    # records build/aot-cache/application.aot
docker build -t my-app .                     # ships and loads the cache
```

The `examples/dockerfile-spring-boot` example is exactly this setup and is verified end to end
on each build.

> Keep the start flags identical between the recording JVM and the runtime JVM. If the
> runtime passes a JVM option the recording does not, the JVM rejects the cache (for example
> `Mismatched values for property jdk.module.enable.native.access`). Add the option to the
> recording with `jvmArguments`:
>
> ```kotlin
> aotCacheTraining {
>     enabled = true
>     containerImage = "eclipse-temurin:25"
>     jvmArguments = listOf("--enable-native-access=ALL-UNNAMED")
> }
> ```
>
> The JVM logs the reason when run with `-Xlog:aot=info`.

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
