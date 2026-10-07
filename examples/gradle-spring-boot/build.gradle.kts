import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.github.vpelikh.aot-cache-training")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

aotCacheTraining {
    enabled = true
    // The packaged application records the cache in its own JVM and the black-box HTTP
    // tests drive it. This is what produces a cache the packaged application (and a
    // container image built from it) can load. Set containerImage to record inside a
    // specific image so the cache matches that image's JVM build and architecture.
    // containerImage = "my-app:latest"
    //
    // The recording JVM's extra options are taken from the bootBuildImage environment below,
    // so the image build is the single source of truth. Set jvmArguments here only to
    // override that derivation.
}

// Configure the JVM flags the cache is recorded and loaded with in one place: the image
// build environment. The plugin reads these and uses them as the recording JVM's options, so
// aotCacheTraining.jvmArguments does not have to repeat them.
//
//  - JAVA_TOOL_OPTIONS reaches the build container, so the buildpack's own AOT training run
//    records with the same flags;
//  - BPE_JDK_JAVA_OPTIONS is baked into the run image as a launch-time JDK_JAVA_OPTIONS by
//    the upstream environment-variables buildpack. A bare JAVA_TOOL_OPTIONS here would set
//    only the build container, and JDK_JAVA_OPTIONS composes with the JAVA_TOOL_OPTIONS the
//    buildpack already contributes.
tasks.named<BootBuildImage>("bootBuildImage") {
    environment.put("JAVA_TOOL_OPTIONS", "--enable-native-access=ALL-UNNAMED")
    environment.put("BPE_JDK_JAVA_OPTIONS", "--enable-native-access=ALL-UNNAMED")
}

// Recording produces build/aot-cache/application.aot.
//
// The plugin's `aotCacheImageJar` task embeds that cache into a copy of the boot JAR, and
// `bootBuildImage` is wired to use that copy, so the image ships the cache with no manual
// step:
//
//   ./gradlew bootBuildImage
//
// The Paketo Spring Boot buildpack finds aot-cache/application.aot inside the application
// content, skips its own training run, and loads the cache at startup with -XX:AOTCache.
