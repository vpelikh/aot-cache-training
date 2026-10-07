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
    // If the runtime starts the JVM with extra options, record with the same ones, otherwise
    // the JVM rejects the cache. Pass them here and give the buildpack the same options at
    // run time through bootBuildImage (below).
    jvmArguments = listOf("--enable-native-access=ALL-UNNAMED")
}

// The image must start the JVM with the same option that aotCacheTraining.jvmArguments
// records with, and the buildpack records the cache in the build container, so the option is
// needed in two places:
//
//  - the build container (plain JAVA_TOOL_OPTIONS), which the buildpack's own AOT training
//    run inherits, so the recorded cache matches the flag;
//  - the run image (BPE_JDK_JAVA_OPTIONS), which the upstream environment-variables buildpack
//    bakes in as a launch-time JDK_JAVA_OPTIONS; a bare JAVA_TOOL_OPTIONS here would only set
//    the build container.
//
// Use JDK_JAVA_OPTIONS, not JAVA_TOOL_OPTIONS, for the runtime half: the JVM prepends it
// without resetting the rest of JAVA_TOOL_OPTIONS, so it composes with what the buildpack
// already contributes.
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
