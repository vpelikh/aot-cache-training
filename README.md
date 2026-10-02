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

- a single switch to record a cache from your tests;
- automatic reuse of the cache in container images via the
  [Paketo Spring Boot buildpack](https://github.com/paketo-buildpacks/spring-boot/pull/609)
  (`aot-cache/application.aot`);
- a `TestExecutionListener` that verifies the environment and eagerly initializes the
  `ApplicationContext` so context creation is part of the training workload.

## The JVM constraint you must know about

Recording an AOT cache with `-XX:AOTCacheOutput` **fails** if any class path entry is a
non-empty directory:

```
[error][aot] Error: non-empty directory '/path/to/target/test-classes/'
Could not create CDS archive
Cannot have non-empty directory in paths
```

This is a JVM restriction (see HotSpot `aotClassLocation.cpp`). Because every standard
test runner puts compiled classes on the class path as **directories**
(`build/classes`, `target/test-classes`), simply injecting `-XX:AOTCacheOutput` into your
normal test JVM cannot record a cache. This is why the original spring-framework approach
still needs tooling: the classes must be packaged into JARs first.

This library therefore ships a small launcher that runs your tests on a **JAR-only class
path**.

> Note: a cache also must be recorded and consumed with the same JVM distribution, OS,
> architecture, class path contents, and many JVM flags. The JVM rejects a cache that does
> not match, so mismatches cost the optimization rather than correctness. Run the training
> run and the application on the same JDK distribution.

## Modules

| Module | Purpose |
| --- | --- |
| `aot-cache-training` | `AotCache` helpers and `AotCacheTestExecutionListener` (auto-registered). |
| `aot-cache-training-trainer` | `TrainingLauncher`, a JUnit Platform entry point that runs tests on a JAR-only class path, plus `TrainingClasspath` utilities. |
| `aot-cache-training-maven-plugin` | Maven `record` and `verify` goals. |
| `aot-cache-training-gradle-plugin` | Gradle `aotCacheTraining` and `verifyAotCache` tasks. |

## Quick start

### Gradle

```groovy
plugins {
    id 'java'
    id 'io.github.vpelikh.aot-cache-training'
}

aotCacheTraining {
    enabled = true
    // packagesToScan = ['com.example']      // optional: limit the training workload
    // failOnTestFailure = true              // default
}
```

```bash
./gradlew aotCacheTraining verifyAotCache
```

This records `build/aot-cache/application.aot`.

### Maven

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

```bash
mvn verify -Daot.cache.record=true
```

This records `target/aot-cache/application.aot`.

No plugin-level dependencies are needed, and the plugin never modifies your test class
path. It derives your JUnit Platform version from the project and resolves a matching
launcher for its own isolated training JVM, so a JUnit 5 and a JUnit 6 project both work
without any extra configuration.

### Using the cache in a container image

Place the recorded file at `aot-cache/application.aot` in your application content. The
Paketo Spring Boot buildpack detects it, skips the training run, and loads it at startup
with `-XX:AOTCache=<path>`.

## How it works

1. **Package classes into JARs.** The build plugins jar `target/test-classes` /
   `build/classes` so the class path has no non-empty directory.
2. **Run the training workload.** `TrainingLauncher` runs the configured tests through the
   JUnit Platform in a JVM started with `-XX:AOTCacheOutput=<build>/aot-cache/application.aot`.
   `AotCacheTestExecutionListener` eagerly initializes each `ApplicationContext` so context
   creation and bean initialization are captured.
3. **Assemble the cache.** The JVM assembles the final cache on clean exit, *after*
   shutdown hooks run.
4. **Verify.** The `verify` goal / task fails the build when recording was requested but no
   non-empty cache was produced. A training run that discovers no tests also fails by
   default, because an empty workload records a large but useless cache.

### JUnit Platform version alignment

The plugin resolves the launcher at your project's own JUnit Platform version and isolates
it to the training JVM. It never adds JUnit (or anything else) to your test class path, so
your dependency tree and your normal `test` task are untouched.

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
- Spring Framework 6.2+ / Spring Boot 3.x+ for the `TestExecutionListener` (it is a no-op
  unless recording is enabled).
- Built against JUnit Platform 6 with JUnit Platform 5 runtime compatibility, and written
  for the JDK 25 toolchain the AOT cache feature requires.

## Building

```bash
./gradlew build
```

The Maven integration tests publish all modules to `build/local-repo` and run a real Maven
build against it.

## License

Apache License, Version 2.0.