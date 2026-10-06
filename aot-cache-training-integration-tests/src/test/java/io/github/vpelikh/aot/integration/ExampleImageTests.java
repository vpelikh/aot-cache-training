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

package io.github.vpelikh.aot.integration;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end verification of every example under {@code examples/}: each example builds a
 * container image and the built image must ship and load the recorded AOT cache.
 *
 * <p>The examples are discovered, not listed, so a new example is covered automatically
 * without touching CI. Each example is built with its own tool (Gradle or Maven), depending
 * on which build file it contains.
 *
 * <p>This needs a Docker daemon (the image build pulls the builder buildpack and runs a
 * container), so it is skipped when Docker is unavailable, keeping the default build green.
 * Maven examples additionally need the modules in the shared local repository, which the
 * test task arranges before running.
 *
 * @author Vasily Pelikh
 */
class ExampleImageTests {

    private static final Pattern BUILT_IMAGE = Pattern.compile("Successfully built image '([^']+)'");

    private static final Path REPO_ROOT = locateRepoRoot();

    static boolean jdkSupportsRecording() {
        return Runtime.version().feature() >= 25;
    }

    static boolean dockerIsAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version").redirectErrorStream(true).start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        }
        catch (Exception ex) {
            return false;
        }
    }

    @TestFactory
    @EnabledIf("io.github.vpelikh.aot.integration.ExampleImageTests#jdkSupportsRecording")
    Stream<DynamicTest> exampleImagesShipAndLoadTheRecordedCache() throws IOException {
        assumeTrue(dockerIsAvailable(), "Docker is not available");
        List<Path> examples = discoverExamples();
        assertThat(examples)
            .as("at least one example must exist under examples/ so this check is meaningful")
            .isNotEmpty();
        return examples.stream()
            .map((example) -> DynamicTest.dynamicTest(example.getFileName().toString(), () -> verifyExample(example)));
    }

    /** Build the example's image and assert a running container loads the recorded AOT cache. */
    private void verifyExample(Path example) throws Exception {
        String image = Files.isRegularFile(example.resolve("build.gradle.kts"))
                ? buildImageWithGradle(example)
                : buildImageWithMaven(example);
        assertImageLoadsTheCache(example.getFileName().toString(), image);
    }

    private String buildImageWithGradle(Path example) throws Exception {
        List<String> command = List.of(
                REPO_ROOT.resolve("gradlew").toString(), "bootBuildImage", "--console=plain", "--no-daemon");
        ProcessResult result = run(command, example, Duration.ofMinutes(20));
        assertThat(result.exitCode())
            .as("bootBuildImage for %s failed:\n%s", example, result.output())
            .isZero();
        return parseBuiltImage(example, result.output());
    }

    private String buildImageWithMaven(Path example) throws Exception {
        // spring-boot:build-image packages from the repackaged JAR, so run the full verify
        // lifecycle (which records and embeds the cache) and then the no-fork image goal.
        List<String> command = List.of(
                mavenExecutable(), "-B", "verify", "spring-boot:build-image-no-fork",
                "-Daot.cache.record=true",
                "-Daot-cache-training.version=" + projectVersion(),
                "-Dmaven.repo.local=" + REPO_ROOT.resolve("build").resolve("local-repo"));
        ProcessResult result = run(command, example, Duration.ofMinutes(20));
        assertThat(result.exitCode())
            .as("spring-boot:build-image-no-fork for %s failed:\n%s", example, result.output())
            .isZero();
        return parseBuiltImage(example, result.output());
    }

    private static String parseBuiltImage(Path example, String output) {
        Matcher matcher = BUILT_IMAGE.matcher(output);
        assertThat(matcher.find())
            .as("the build of %s must report the built image, output was:\n%s", example, output)
            .isTrue();
        return matcher.group(1);
    }

    /** Start the image and require it to serve HTTP while the buildpack loads the AOT cache. */
    private static void assertImageLoadsTheCache(String label, String image) throws Exception {
        int port = findFreePort();
        String container = "aot-example-verify-" + port;
        try {
            // -Xlog:aot=info makes the JVM log whether it actually opened the cache, which is
            // the difference between "the flag was passed" and "the cache was really loaded".
            run(List.of("docker", "run", "-d", "--name", container,
                    "-e", "JAVA_TOOL_OPTIONS=-Xlog:aot=info",
                    "-p", port + ":8080", image), REPO_ROOT, Duration.ofMinutes(5));
            awaitHttp(container, port);
            String logs = run(List.of("docker", "logs", container), REPO_ROOT, Duration.ofMinutes(2)).output();
            assertThat(logs)
                .as("the buildpack must enable the AOT cache in the %s image", label)
                .contains("JVM AOT Cache Enabled")
                .contains("-XX:AOTCache=");
            assertThat(logs)
                .as("the JVM in the %s image must actually open the recorded AOT cache", label)
                .contains("Opened AOT cache");
        }
        finally {
            run(List.of("docker", "rm", "-f", container), REPO_ROOT, Duration.ofMinutes(2));
        }
    }

    private static void awaitHttp(String container, int port) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        while (System.nanoTime() < deadline) {
            try {
                HttpURLConnection connection = (HttpURLConnection) URI
                    .create("http://localhost:" + port + "/").toURL().openConnection();
                connection.setConnectTimeout(1000);
                connection.setReadTimeout(1000);
                try {
                    if (connection.getResponseCode() == 200) {
                        try (InputStream in = connection.getInputStream()) {
                            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                            assertThat(body)
                                .as("the application must serve a non-empty response with the cache loaded")
                                .isNotBlank();
                            return;
                        }
                    }
                }
                finally {
                    connection.disconnect();
                }
            }
            catch (IOException ex) {
                // not up yet
            }
            String running = run(List.of("docker", "inspect", "-f", "{{.State.Running}}", container),
                    REPO_ROOT, Duration.ofMinutes(1)).output().trim();
            if (!running.equals("true")) {
                String logs = run(List.of("docker", "logs", container), REPO_ROOT, Duration.ofMinutes(1)).output();
                throw new IllegalStateException("The container exited before serving HTTP. Logs:\n" + logs);
            }
            Thread.sleep(500);
        }
        String logs = run(List.of("docker", "logs", container), REPO_ROOT, Duration.ofMinutes(1)).output();
        throw new IllegalStateException("The container never served HTTP. Logs:\n" + logs);
    }

    private static List<Path> discoverExamples() throws IOException {
        Path examples = REPO_ROOT.resolve("examples");
        if (!Files.isDirectory(examples)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(examples)) {
            return stream
                .filter(Files::isDirectory)
                .filter((dir) -> Files.isRegularFile(dir.resolve("build.gradle.kts"))
                        || Files.isRegularFile(dir.resolve("pom.xml")))
                .sorted()
                .toList();
        }
    }

    private static ProcessResult run(List<String> command, Path directory, Duration timeout) throws Exception {
        Path log = Files.createTempFile("example-verify", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
            Process process = builder.start();
            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(String.join(" ", command) + " timed out after " + timeout);
            }
            return new ProcessResult(process.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
        }
        finally {
            Files.deleteIfExists(log);
        }
    }

    private static String mavenExecutable() {
        String home = System.getenv("MAVEN_HOME");
        return (home != null) ? Path.of(home, "bin", "mvn").toString() : "mvn";
    }

    private static String projectVersion() throws IOException {
        for (String line : Files.readAllLines(REPO_ROOT.resolve("gradle.properties"), StandardCharsets.UTF_8)) {
            if (line.startsWith("version=")) {
                return line.substring("version=".length()).trim();
            }
        }
        throw new IllegalStateException("No version= entry in gradle.properties");
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path locateRepoRoot() {
        String configured = System.getProperty("aot.integration.repoRoot");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath();
        }
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts")) && Files.isDirectory(dir.resolve("examples"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("Could not locate the repository root from "
                + System.getProperty("user.dir"));
    }

    private record ProcessResult(int exitCode, String output) {
    }

}