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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

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
    void recordingJvmArgumentsAreDerivedFromTheImageEnvironment() throws IOException {
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

                tasks.named<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
                    environment.put("JAVA_TOOL_OPTIONS", "--enable-native-access=ALL-UNNAMED -Xmx512m")
                    environment.put("BPE_JDK_JAVA_OPTIONS", "--enable-native-access=ALL-UNNAMED")
                }

                tasks.register("probeJvmArguments") {
                    doLast {
                        println("JVM_ARGS=" + aotCacheTraining.jvmArguments.get().joinToString(","))
                    }
                }
                """.formatted(SPRING_BOOT_VERSION, SPRING_BOOT_VERSION));

        BuildResult result = runner("probeJvmArguments").build();

        assertThat(result.getOutput())
            .contains("JVM_ARGS=--enable-native-access=ALL-UNNAMED,-Xmx512m");
    }

    @Test
    void explicitJvmArgumentsOverrideTheImageEnvironment() throws IOException {
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
                    jvmArguments = listOf("-Xmx256m")
                }

                tasks.named<org.springframework.boot.gradle.tasks.bundling.BootBuildImage>("bootBuildImage") {
                    environment.put("JAVA_TOOL_OPTIONS", "--enable-native-access=ALL-UNNAMED")
                }

                tasks.register("probeJvmArguments") {
                    doLast {
                        println("JVM_ARGS=" + aotCacheTraining.jvmArguments.get().joinToString(","))
                    }
                }
                """.formatted(SPRING_BOOT_VERSION, SPRING_BOOT_VERSION));

        BuildResult result = runner("probeJvmArguments").build();

        assertThat(result.getOutput()).contains("JVM_ARGS=-Xmx256m");
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
