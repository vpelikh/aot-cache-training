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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test that runs a real Maven build with the locally published
 * {@code aot-cache-training-maven-plugin} and the {@code aot-cache-training} library.
 *
 * <p>Exercises the full flow: the {@code record} goal injects
 * {@code -XX:AOTCacheOutput} into Surefire's {@code argLine}, a real test JVM records the
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
	void setUp() throws IOException {
		Path repo = localRepository();
		assertThat(repo).as("run './gradlew publishAllPublicationsToLocalTestRepository' first").exists();
	}

	@Test
	void recordsAndVerifiesCacheFromSurefireTests() throws Exception {
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
	void verifyGoalFailsClosedWhenNoCacheRecorded() throws Exception {
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

		// Recording is enabled but the tests are skipped, so no cache is produced and the
		// verify goal must fail closed.
		MavenResult result = runMaven("verify", "-Daot.cache.record=true", "-DskipTests");

		assertThat(result.exitCode()).as("maven output:%n%s", result.output()).isNotZero();
		assertThat(result.output()).contains("no non-empty cache");
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
								<dependencies>
									<dependency>
										<groupId>io.github.vpelikh</groupId>
										<artifactId>aot-cache-training</artifactId>
										<version>%s</version>
									</dependency>
								</dependencies>
							</plugin>
							<plugin>
								<groupId>org.apache.maven.plugins</groupId>
								<artifactId>maven-surefire-plugin</artifactId>
								<version>3.5.2</version>
							</plugin>
						</plugins>
					</build>
					<dependencies>
						<dependency>
							<groupId>org.junit.jupiter</groupId>
							<artifactId>junit-jupiter</artifactId>
							<version>5.14.4</version>
							<scope>test</scope>
						</dependency>
					</dependencies>
				</project>
				""".formatted(VERSION, VERSION));
	}

	private MavenResult runMaven(String... goals) throws IOException, InterruptedException {
		java.util.List<String> command = new java.util.ArrayList<>();
		command.add(mavenExecutable());
		command.add("-B");
		command.add("-Dmaven.repo.local=" + localRepository());
		command.addAll(java.util.Arrays.asList(goals));

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