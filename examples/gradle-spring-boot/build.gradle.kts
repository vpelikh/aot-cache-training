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
