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

package io.github.vpelikh.aot.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test that runs a real Maven build with the locally published
 * {@code aot-cache-training-maven-plugin} and the {@code aot-cache-training} library.
 *
 * <p>Exercises the full flow: the {@code record} goal packages the classes into JARs and
 * runs the tests through the trainer on a JAR-only class path, a real JVM records the
 * cache, and the {@code verify} goal accepts it.
 *
 * <p>Only runs on JDK 25+, where the single-step recording flag exists.
 *
 * @author Vasily Pelikh
 */
@EnabledIf("io.github.vpelikh.aot.maven.AotCacheMavenIntegrationTests#jdkSupportsRecording")
class AotCacheMavenIntegrationTests {

	static final String VERSION = "0.1.0";

	static boolean jdkSupportsRecording() {
		return Runtime.version().feature() >= 25;
	}

	@TempDir
	Path projectDir;

	@BeforeEach
	void setUp() {
		Path repo = localRepository();
		assertThat(repo).as("run './gradlew publishForIntegrationTests' first").exists();
	}

	@Test
	void recordsAndVerifiesCacheFromTests() throws Exception {
		writePom();
		write("src/test/java/sample/SampleTests.java", """
				package sample;

				import org.junit.jupiter.api.Test;

				class SampleTests {

					@Test
					void passes() {
					}

				}
				""");

		MavenResult result = runMaven("verify", "-Daot.cache.record=true");

		assertThat(result.exitCode()).as("maven output:%n%s", result.output()).isZero();
		assertThat(result.output()).contains("Verified AOT cache");
		assertThat(this.projectDir.resolve("target/aot-cache/application.aot")).exists();
	}

	@Test
	void recordGoalFailsWhenNoTestsAreDiscovered() throws Exception {
		writePom();
		// No test sources at all.
		write("src/main/java/sample/App.java", """
				package sample;

				class App {
				}
				""");

		MavenResult result = runMaven("verify", "-Daot.cache.record=true");

		assertThat(result.exitCode()).as("maven output:%n%s", result.output()).isNotZero();
		assertThat(result.output()).contains("No tests were discovered");
	}

	private void writePom() throws IOException {
		write("pom.xml", """
				<project xmlns="http://maven.apache.org/POM/4.0.0">
					<modelVersion>4.0.0</modelVersion>
					<groupId>sample</groupId>
					<artifactId>sample</artifactId>
					<version>1.0.0</version>
					<properties>
						<maven.compiler.release>17</maven.compiler.release>
						<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
					</properties>
					<build>
						<plugins>
							<plugin>
								<groupId>io.github.vpelikh</groupId>
								<artifactId>aot-cache-training-maven-plugin</artifactId>
								<version>%s</version>
								<executions>
									<execution>
										<id>aot-record</id>
										<goals>
											<goal>record</goal>
										</goals>
									</execution>
									<execution>
										<id>aot-verify</id>
										<goals>
											<goal>verify</goal>
										</goals>
									</execution>
								</executions>
							</plugin>
						</plugins>
					</build>
					<dependencies>
						<dependency>
							<groupId>org.junit.jupiter</groupId>
							<artifactId>junit-jupiter</artifactId>
							<version>6.1.3</version>
							<scope>test</scope>
						</dependency>
					</dependencies>
				</project>
				""".formatted(VERSION));
	}

	private MavenResult runMaven(String... goals) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>();
		command.add(mavenExecutable());
		command.add("-B");
		command.add("-Dmaven.repo.local=" + localRepository());
		command.addAll(List.of(goals));

		Process process = new ProcessBuilder(command).directory(this.projectDir.toFile())
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes());
		int exitCode = process.waitFor();
		return new MavenResult(exitCode, output);
	}

	private String mavenExecutable() {
		String home = System.getenv("MAVEN_HOME");
		if (home != null) {
			return Path.of(home, "bin", "mvn").toString();
		}
		return "mvn";
	}

	private Path localRepository() {
		Path root = Path.of(System.getProperty("user.dir")).getParent();
		return root.resolve("build").resolve("local-repo");
	}

	private void write(String relativePath, String content) throws IOException {
		Path file = this.projectDir.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	record MavenResult(int exitCode, String output) {
	}

}