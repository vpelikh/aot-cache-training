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

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests for {@link AotCacheTrainingPlugin} using Gradle TestKit. On JDK 25+ the
 * tests exercise a real AOT cache recording run through the trainer.
 *
 * @author Vasily Pelikh
 */
class AotCacheTrainingPluginFunctionalTests {

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
                    id("io.github.vpelikh.aot-cache-training")
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    testImplementation(platform("org.junit:junit-bom:6.1.3"))
                    testImplementation("org.junit.jupiter:junit-jupiter")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                }

                tasks.test {
                    useJUnitPlatform()
                }

                aotCacheTraining {
                    enabled = true
                }
                """);
        write("src/test/java/sample/SampleTests.java", """
                package sample;

                import org.junit.jupiter.api.Test;

                class SampleTests {

                    @Test
                    void passes() {
                    }

                }
                """);
    }

    @Test
    @EnabledIf("io.github.vpelikh.aot.gradle.AotCacheTrainingPluginFunctionalTests#jdkSupportsRecording")
    void recordsCacheFromTestsOnJarOnlyClasspath() {
        BuildResult result = runner("aotCacheTraining", "verifyAotCache").build();

        assertThat(result.task(":aotCacheTraining").getOutcome()).isNotNull();
        assertThat(result.getOutput()).contains("1 tests successful");
        assertThat(this.projectDir.resolve("build/aot-cache/application.aot")).exists();
        assertThat(this.projectDir.resolve("build/aot-cache/application.aot").toFile().length()).isGreaterThan(0);
    }

    @Test
    void recordingIsInertWhenDisabled() throws IOException {
        write("build.gradle.kts", """
                plugins {
                    java
                    id("io.github.vpelikh.aot-cache-training")
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    testImplementation(platform("org.junit:junit-bom:6.1.3"))
                    testImplementation("org.junit.jupiter:junit-jupiter")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                }

                tasks.test {
                    useJUnitPlatform()
                }
                """);

        BuildResult result = runner("aotCacheTraining", "verifyAotCache").build();

        assertThat(result.getOutput()).doesNotContain("Recording an AOT cache");
    }

    @Test
    @EnabledIf("io.github.vpelikh.aot.gradle.AotCacheTrainingPluginFunctionalTests#jdkSupportsRecording")
    void failingTestsFailTheTrainingRunByDefault() throws IOException {
        write("src/test/java/sample/SampleTests.java", """
                package sample;

                import org.junit.jupiter.api.Test;

                class SampleTests {

                    @Test
                    void fails() {
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
                    id("io.github.vpelikh.aot-cache-training")
                }

                repositories {
                    mavenCentral()
                }

                dependencies {
                    testImplementation(platform("org.junit:junit-bom:6.1.3"))
                    testImplementation("org.junit.jupiter:junit-jupiter")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                }

                tasks.test {
                    useJUnitPlatform()
                }

                aotCacheTraining {
                    enabled = true
                    failOnTestFailure = false
                }
                """);
        write("src/test/java/sample/SampleTests.java", """
                package sample;

                import org.junit.jupiter.api.Test;

                class SampleTests {

                    @Test
                    void fails() {
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
        // Remove the test so nothing is discovered.
        java.nio.file.Files.delete(this.projectDir.resolve("src/test/java/sample/SampleTests.java"));
        write("src/test/java/sample/NotATest.java", """
                package sample;

                class NotATest {
                }
                """);

        BuildResult result = runner("aotCacheTraining").buildAndFail();

        assertThat(result.getOutput()).contains("No tests were discovered");
    }

    private GradleRunner runner(String... arguments) {
        java.util.List<String> args = new java.util.ArrayList<>(java.util.Arrays.asList(arguments));
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
