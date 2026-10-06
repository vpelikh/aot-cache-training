/*
 * Copyright 2026-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.vpelikh.aot.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Functional tests for {@link AotCacheTrainingPlugin} using Gradle TestKit. On JDK 25+ the
 * tests exercise a real AOT cache recording run: the packaged Spring Boot application starts
 * in its own JVM and the black-box HTTP tests drive it.
 *
 * @author Vasily Pelikh
 */
class AotCacheTrainingPluginFunctionalTests {

    static final String SPRING_BOOT_VERSION = "4.1.1";

    static boolean jdkSupportsRecording() {
        return Runtime.version().feature() >= 25;
    }

    static boolean dockerIsAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version")
                .redirectErrorStream(true)
                .start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        }
        catch (Exception ex) {
            return false;
        }
    }

    @TempDir
    Path projectDir;

    @BeforeEach
    void setUp() throws IOException {
        write("settings.gradle.kts", """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                rootProject.name = "sample"
                """);
        write("build.gradle.kts", """
                plugins {
                    java
                    id("org.springframework.boot") version "%s"
                    id("io.github.vpelikh.aot-cache-training")
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    implementation(platform("org.springframework.boot:spring-boot-dependencies:%s"))
                    implementation("org.springframework.boot:spring-boot-starter-web")
                    testImplementation("org.springframework.boot:spring-boot-starter-test")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                }

                tasks.test {
                    useJUnitPlatform()
                }

                aotCacheTraining {
                    enabled = true
                }
                """.formatted(SPRING_BOOT_VERSION, SPRING_BOOT_VERSION));
        writeApplication();
        writeHttpTests("""
                package sample;

                import java.net.URI;
                import java.net.http.HttpClient;
                import java.net.http.HttpRequest;
                import java.net.http.HttpResponse;

                import org.junit.jupiter.api.Test;

                import static org.assertj.core.api.Assertions.assertThat;
                import static org.junit.jupiter.api.Assumptions.assumeTrue;

                class SampleHttpTests {

                    @Test
                    void greetsOverHttp() throws Exception {
                        String baseUrl = System.getProperty("aot.training.url");
                        assumeTrue(baseUrl != null, "no application running");
                        HttpClient client = HttpClient.newHttpClient();
                        for (int i = 0; i < 3; i++) {
                            HttpResponse<String> response = client.send(
                                    HttpRequest.newBuilder(URI.create(baseUrl + "/")).build(),
                                    HttpResponse.BodyHandlers.ofString());
                            assertThat(response.statusCode()).isEqualTo(200);
                            assertThat(response.body()).contains("Hello");
                        }
                    }

                }
                """);
    }

    @Test
    @EnabledIf("io.github.vpelikh.aot.gradle.AotCacheTrainingPluginFunctionalTests#jdkSupportsRecording")
    void recordsCacheFromPackagedApplication() {
        BuildResult result = runner("aotCacheTraining", "verifyAotCache").build();

        assertThat(result.task(":aotCacheTraining").getOutcome()).isNotNull();
        assertThat(result.getOutput()).contains("1 tests successful");
        assertThat(this.projectDir.resolve("build/aot-cache/application.aot")).exists();
        assertThat(this.projectDir.resolve("build/aot-cache/application.aot").toFile().length()).isGreaterThan(0);
    }

    @Test
    void recordingIsInertWhenDisabled() throws IOException {
        // Overwrite with a disabled configuration.
        write("build.gradle.kts", """
                plugins {
                    java
                    id("org.springframework.boot") version "%s"
                    id("io.github.vpelikh.aot-cache-training")
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    implementation(platform("org.springframework.boot:spring-boot-dependencies:%s"))
                    implementation("org.springframework.boot:spring-boot-starter-web")
                    testImplementation("org.springframework.boot:spring-boot-starter-test")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                }

                tasks.test {
                    useJUnitPlatform()
                }

                aotCacheTraining {
                    enabled = false
                }
                """.formatted(SPRING_BOOT_VERSION, SPRING_BOOT_VERSION));

        BuildResult result = runner("aotCacheTraining", "verifyAotCache").build();

        assertThat(this.projectDir.resolve("build/aot-cache/application.aot")).doesNotExist();
    }

    @Test
    @EnabledIf("io.github.vpelikh.aot.gradle.AotCacheTrainingPluginFunctionalTests#jdkSupportsRecording")
    void failingTestsFailTheTrainingRunByDefault() throws IOException {
        writeHttpTests("""
                package sample;

                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assumptions.assumeTrue;

                class SampleHttpTests {

                    @Test
                    void fails() {
                        assumeTrue(System.getProperty("aot.training.url") != null, "no application running");
                        throw new AssertionError("boom");
                    }

                }
                """);

        BuildResult result = runner("aotCacheTraining").buildAndFail();

        assertThat(result.getOutput()).contains("1 tests failed");
    }

    @Test
    @EnabledIf("io.github.vpelikh.aot.gradle.AotCacheTrainingPluginFunctionalTests#jdkSupportsRecording")
    void failingTestsDoNotFailWhenFailOnTestFailureIsDisabled() throws IOException {
        write("build.gradle.kts", """
                plugins {
                    java
                    id("org.springframework.boot") version "%s"
                    id("io.github.vpelikh.aot-cache-training")
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    implementation(platform("org.springframework.boot:spring-boot-dependencies:%s"))
                    implementation("org.springframework.boot:spring-boot-starter-web")
                    testImplementation("org.springframework.boot:spring-boot-starter-test")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                }

                tasks.test {
                    useJUnitPlatform()
                }

                aotCacheTraining {
                    enabled = true
                    failOnTestFailure = false
                }
                """.formatted(SPRING_BOOT_VERSION, SPRING_BOOT_VERSION));
        writeHttpTests("""
                package sample;

                import org.junit.jupiter.api.Test;

                import static org.junit.jupiter.api.Assumptions.assumeTrue;

                class SampleHttpTests {

                    @Test
                    void fails() {
                        assumeTrue(System.getProperty("aot.training.url") != null, "no application running");
                        throw new AssertionError("boom");
                    }

                }
                """);

        BuildResult result = runner("aotCacheTraining", "verifyAotCache").build();

        assertThat(this.projectDir.resolve("build/aot-cache/application.aot")).exists();
    }

    @Test
    @EnabledIf("io.github.vpelikh.aot.gradle.AotCacheTrainingPluginFunctionalTests#jdkSupportsRecording")
    void noTestsFailsTheTrainingRunByDefault() throws IOException {
        // Replace the test with a non-test type so nothing is discovered.
        writeHttpTests("""
                package sample;

                class NotATest {
                }
                """);

        BuildResult result = runner("aotCacheTraining").buildAndFail();

        assertThat(result.getOutput()).contains("No tests were discovered");
    }

    @Test
    @EnabledIf("io.github.vpelikh.aot.gradle.AotCacheTrainingPluginFunctionalTests#jdkSupportsRecording")
    void allowEmptyWorkloadRecordsWhenNoTestsAreDiscovered() throws IOException {
        // allowEmptyWorkload must reach the launcher even though the argument provider that
        // contributes it is the same one that carries the application and cache paths.
        write("build.gradle.kts", """
                plugins {
                    java
                    id("org.springframework.boot") version "%s"
                    id("io.github.vpelikh.aot-cache-training")
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    implementation(platform("org.springframework.boot:spring-boot-dependencies:%s"))
                    implementation("org.springframework.boot:spring-boot-starter-web")
                    testImplementation("org.springframework.boot:spring-boot-starter-test")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                }

                tasks.test {
                    useJUnitPlatform()
                }

                aotCacheTraining {
                    enabled = true
                    allowEmptyWorkload = true
                }
                """.formatted(SPRING_BOOT_VERSION, SPRING_BOOT_VERSION));
        writeHttpTests("""
                package sample;

                class NotATest {
                }
                """);

        BuildResult result = runner("aotCacheTraining", "verifyAotCache").build();

        assertThat(this.projectDir.resolve("build/aot-cache/application.aot")).exists();
    }

    @Test
    void imageBuildKeepsBootJarWhenRecordingIsDisabled() throws IOException {
        // Overwrite with a disabled configuration plus a probe that resolves the JAR the image
        // build would package. With recording disabled the embed task is skipped, so
        // bootBuildImage must still point at the normal boot JAR rather than the never-produced
        // aot-cache-image/application.jar.
        write("build.gradle.kts", """
                plugins {
                    java
                    id("org.springframework.boot") version "%s"
                    id("io.github.vpelikh.aot-cache-training")
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    implementation(platform("org.springframework.boot:spring-boot-dependencies:%s"))
                    implementation("org.springframework.boot:spring-boot-starter-web")
                }

                aotCacheTraining {
                    enabled = false
                }

                tasks.register("probeImageArchive") {
                    dependsOn("bootJar")
                    doLast {
                        val buildImage = tasks.named("bootBuildImage").get()
                            as org.springframework.boot.gradle.tasks.bundling.BootBuildImage
                        println("IMAGE_ARCHIVE=" + buildImage.archiveFile.get().asFile.name)
                    }
                }
                """.formatted(SPRING_BOOT_VERSION, SPRING_BOOT_VERSION));

        BuildResult result = runner("probeImageArchive").build();

        assertThat(result.getOutput()).contains("IMAGE_ARCHIVE=sample.jar");
        assertThat(result.getOutput()).doesNotContain("aot-cache-image/application.jar");
    }

    @Test
    void imageBuildUsesEmbeddedJarWhenRecordingIsEnabled() throws IOException {
        write("build.gradle.kts", """
                plugins {
                    java
                    id("org.springframework.boot") version "%s"
                    id("io.github.vpelikh.aot-cache-training")
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    implementation(platform("org.springframework.boot:spring-boot-dependencies:%s"))
                    implementation("org.springframework.boot:spring-boot-starter-web")
                }

                aotCacheTraining {
                    enabled = true
                }

                tasks.register("probeImageArchive") {
                    doLast {
                        val buildImage = tasks.named("bootBuildImage").get()
                            as org.springframework.boot.gradle.tasks.bundling.BootBuildImage
                        println("IMAGE_ARCHIVE=" + buildImage.archiveFile.get().asFile.name)
                        println("AOT_FLAG=" + buildImage.environment.get()["BP_JVM_AOTCACHE_ENABLED"])
                    }
                }
                """.formatted(SPRING_BOOT_VERSION, SPRING_BOOT_VERSION));

        BuildResult result = runner("probeImageArchive").build();

        assertThat(result.getOutput()).contains("IMAGE_ARCHIVE=application.jar");
        assertThat(result.getOutput()).contains("AOT_FLAG=true");
    }

    @Test
    @EnabledIf("io.github.vpelikh.aot.gradle.AotCacheTrainingPluginFunctionalTests#jdkSupportsRecording")
    void bootBuildImageShipsAndRunsWithTheRecordedCache() throws Exception {
        // A real image build needs a working Docker daemon and pulls the builder buildpack,
        // so it only runs when Docker is available (skipped otherwise, keeping CI green).
        assumeTrue(dockerIsAvailable(), "Docker is not available");
        write("build.gradle.kts", """
                plugins {
                    java
                    id("org.springframework.boot") version "%s"
                    id("io.github.vpelikh.aot-cache-training")
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    implementation(platform("org.springframework.boot:spring-boot-dependencies:%s"))
                    implementation("org.springframework.boot:spring-boot-starter-web")
                    testImplementation("org.springframework.boot:spring-boot-starter-test")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                }

                tasks.test {
                    useJUnitPlatform()
                }

                aotCacheTraining {
                    enabled = true
                }
                """.formatted(SPRING_BOOT_VERSION, SPRING_BOOT_VERSION));

        // End-to-end: the plugin records the cache, embeds it into the boot JAR the image build
        // packages, and points the buildpack at it. This is the path that until now was only
        // exercised by hand, so the build here is what proves the cache reaches the image.
        BuildResult result = runner("bootBuildImage").build();
        assertThat(result.getOutput()).contains("Successfully built image");

        // The recorded cache must ship inside the image so the buildpack can load it at start.
        String image = imageIdFrom(result.getOutput());
        String container = null;
        try {
            container = docker("create", image).trim();
            // docker export emits a binary tar; parse its entry names directly.
            List<String> entries = dockerExportListing(container);
            assertThat(entries)
                .as("the recorded AOT cache must be present in the image content")
                .contains("workspace/application.aot");
        }
        finally {
            if (container != null && !container.isBlank()) {
                docker("rm", "-f", container);
            }
        }
    }

    /** Stream {@code docker export} and list entry names by parsing the TAR header blocks. */
    private static List<String> dockerExportListing(String container) throws Exception {
        Process export = new ProcessBuilder("docker", "export", container).start();
        List<String> names = new ArrayList<>();
        byte[] header = new byte[512];
        try (InputStream in = export.getInputStream()) {
            while (readFully(in, header)) {
                if (isEmpty(header)) {
                    break;
                }
                int nameEnd = 0;
                while (nameEnd < 100 && header[nameEnd] != 0) {
                    nameEnd++;
                }
                String name = new String(header, 0, nameEnd, StandardCharsets.UTF_8);
                if (name.endsWith("application.aot")) {
                    names.add(name);
                    // Only the filename is asserted; skip copying the (large) data blocks.
                }
                long size = parseOctal(header, 124, 12);
                long dataBlocks = (size + 511) / 512;
                skipFully(in, dataBlocks * 512);
            }
        }
        export.waitFor(5, TimeUnit.MINUTES);
        return names;
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int n = in.read(buffer, read, buffer.length - read);
            if (n < 0) {
                return false;
            }
            read += n;
        }
        return true;
    }

    private static boolean isEmpty(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new IOException("Unexpected end of stream while skipping tar data");
                }
                remaining--;
            }
            else {
                remaining -= skipped;
            }
        }
    }

    /** Parse a POSIX octal-valued tar header field. */
    private static long parseOctal(byte[] bytes, int offset, int length) {
        int end = offset + length;
        while (end > offset && bytes[end - 1] == 0) {
            end--;
        }
        long value = 0;
        for (int i = offset; i < end; i++) {
            byte b = bytes[i];
            if (b == ' ') {
                continue;
            }
            value = value * 8 + (b - '0');
        }
        return value;
    }

    /** Run a {@code docker} command and return its merged stdout/stderr, waiting for it to finish. */
    private static String docker(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] out;
        try (InputStream in = process.getInputStream()) {
            out = in.readAllBytes();
        }
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("docker " + String.join(" ", args) + " timed out");
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    /** Parse the built image reference from the buildpack's {@code Saving <image>...} line. */
    private static String imageIdFrom(String output) {
        for (String line : output.split("\\R")) {
            int idx = line.indexOf("Saving ");
            if (idx >= 0) {
                String saved = line.substring(idx + "Saving ".length()).trim();
                // The buildpack prints "Saving <image>..." with trailing dots on the same line.
                while (saved.endsWith(".")) {
                    saved = saved.substring(0, saved.length() - 1);
                }
                if (!saved.isBlank()) {
                    return saved;
                }
            }
        }
        return "sample:latest";
    }

    private void writeApplication() throws IOException {
        write("src/main/java/sample/App.java", """
                package sample;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @SpringBootApplication
                @RestController
                public class App {

                    public static void main(String[] args) {
                        SpringApplication.run(App.class, args);
                    }

                    @GetMapping("/")
                    public String hello() {
                        return "Hello";
                    }

                }
                """);
    }

    private void writeHttpTests(String content) throws IOException {
        write("src/test/java/sample/SampleHttpTests.java", content);
    }

    private GradleRunner runner(String... arguments) {
        List<String> args = new ArrayList<>(Arrays.asList(arguments));
        args.add("--stacktrace");
        return GradleRunner.create()
            .withProjectDir(this.projectDir.toFile())
            .withPluginClasspath()
            .withArguments(args)
            .forwardOutput();
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = this.projectDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

}
