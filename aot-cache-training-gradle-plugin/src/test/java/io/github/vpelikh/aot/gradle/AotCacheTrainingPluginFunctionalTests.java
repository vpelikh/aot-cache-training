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
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Functional tests for {@link AotCacheTrainingPlugin} using Gradle TestKit.
 *
 * @author Vasily Pelikh
 */
class AotCacheTrainingPluginFunctionalTests {

	@TempDir
	Path projectDir;

	@BeforeEach
	void setUp() throws IOException {
		write("settings.gradle", """
				rootProject.name = 'sample'
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
	void printsRecordingArgumentWhenEnabled() throws IOException {
		write("build.gradle", """
				plugins {
					id 'java'
					id 'io.github.vpelikh.aot-cache-training'
				}

				repositories {
					mavenCentral()
				}

				dependencies {
					testImplementation platform('org.junit:junit-bom:5.14.4')
					testImplementation 'org.junit.jupiter:junit-jupiter'
					testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
				}

				test {
					useJUnitPlatform()
				}

				aotCacheTraining {
					enabled = true
				}

				tasks.register('printTestArgs') {
					def testTask = tasks.named('test').get()
					doLast {
						def contribution = []
						testTask.jvmArgumentProviders.each { contribution.addAll(it.asArguments()) }
						println 'JVMARGS=' + (testTask.jvmArgs + contribution).join('|')
					}
				}
				""");

		BuildResult result = runner("printTestArgs").build();

		// The recording flag must be wired to the conventional cache path.
		assertThat(result.getOutput()).contains("-XX:AOTCacheOutput=");
		assertThat(result.getOutput()).contains("aot-cache");
	}

	@Test
	void recordingIsInertWhenDisabled() throws IOException {
		write("build.gradle", """
				plugins {
					id 'java'
					id 'io.github.vpelikh.aot-cache-training'
				}

				repositories {
					mavenCentral()
				}

				dependencies {
					testImplementation platform('org.junit:junit-bom:5.14.4')
					testImplementation 'org.junit.jupiter:junit-jupiter'
					testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
				}

				test {
					useJUnitPlatform()
				}
				""");

		BuildResult result = runner("test").build();

		assertThat(result.getOutput()).doesNotContain("-XX:AOTCacheOutput=");
		assertThat(result.task(":test").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
	}

	@Test
	void verifyTaskFailsClosedWhenNoCacheWasRecorded() throws IOException {
		write("build.gradle", """
				plugins {
					id 'java'
					id 'io.github.vpelikh.aot-cache-training'
				}

				repositories {
					mavenCentral()
				}

				dependencies {
					testImplementation platform('org.junit:junit-bom:5.14.4')
					testImplementation 'org.junit.jupiter:junit-jupiter'
					testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
				}

				test {
					useJUnitPlatform()
				}

				aotCacheTraining {
					enabled = true
				}
				""");

		// No tests run, so no cache is recorded; the verification task must fail closed with
		// actionable guidance.
		BuildResult result = runner("verifyAotCache", "-x", "test").buildAndFail();

		assertThat(result.getOutput()).contains("AOT cache recording was enabled");
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