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

    // Record inside the same image the Dockerfile builds, so the cache matches that image's
    // JVM build and architecture. Without this the cache would be recorded on the build
    // host's JVM and the container's JVM would reject it.
    containerImage = "eclipse-temurin:25"
}

// Recording produces build/aot-cache/application.aot (recorded inside the image above). The
// Dockerfile runs a plain `docker build` that copies that cache and the boot JAR, flattens
// the JAR to the layout the cache expects, and starts the JVM with -XX:AOTCache. This is the
// hand-rolled alternative to the Paketo buildpack used by the other examples:
//
//   ./gradlew aotCacheTraining verifyAotCache           # records build/aot-cache/application.aot
//   docker build -t dockerfile-spring-boot-example .    # ships and loads the cache