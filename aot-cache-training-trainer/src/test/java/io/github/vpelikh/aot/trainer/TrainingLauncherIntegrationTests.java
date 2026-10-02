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

package io.github.vpelikh.aot.trainer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.vpelikh.aot.AotCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for {@link TrainingLauncher} on a JAR-only class path, which is the only
 * class-path shape the JVM accepts for AOT cache recording.
 *
 * <p>Only runs on JDK 25+.
 *
 * @author Vasily Pelikh
 */
@EnabledIf("io.github.vpelikh.aot.trainer.TrainingLauncherIntegrationTests#jdkSupportsRecording")
class TrainingLauncherIntegrationTests {

	static boolean jdkSupportsRecording() {
		return Runtime.version().feature() >= 25;
	}

	@Test
	void recordsAndReusesCacheOnJarOnlyClasspath(@TempDir Path tempDir) throws Exception {
		Path cacheFile = tempDir.resolve("aot-cache").resolve("application.aot");
		Files.createDirectories(cacheFile.getParent());

		ProcessResult recording = runJvm(List.of("-XX:AOTCacheOutput=" + cacheFile),
				List.of("--select-class", SampleTrainingTests.class.getName()));
		assertThat(recording.exitCode()).as("training run exit code; output:%n%s", recording.output()).isZero();
		assertThat(recording.output()).contains("1 tests successful");
		assertThat(cacheFile).as("cache file after training run; output:%n%s", recording.output()).exists();
		assertThat(AotCache.verifyRecordedCache(cacheFile)).isGreaterThan(0);

		// A fresh JVM must accept (load) the cache; the JVM rejects caches that do not
		// match the runtime and class path, so a successful run proves compatibility.
		ProcessResult reuse = runJvm(List.of("-XX:AOTCache=" + cacheFile, "-XX:AOTMode=on"),
				List.of("--select-class", SampleTrainingTests.class.getName()));
		assertThat(reuse.exitCode()).as("reuse run exit code; output:%n%s", reuse.output()).isZero();
	}

	@Test
	void failsWhenNoTestsAreDiscovered(@TempDir Path tempDir) throws Exception {
		Path cacheFile = tempDir.resolve("aot-cache").resolve("application.aot");
		Files.createDirectories(cacheFile.getParent());

		ProcessResult result = runJvm(List.of("-XX:AOTCacheOutput=" + cacheFile),
				List.of("--select-package", "does.not.exist"));
		assertThat(result.exitCode()).as("output:%n%s", result.output()).isEqualTo(2);
		assertThat(result.output()).contains("No tests were discovered");
	}

	private ProcessResult runJvm(List<String> jvmArgs, List<String> programArgs) throws IOException {
		List<String> command = new ArrayList<>();
		command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		command.addAll(jvmArgs);
		command.add("-cp");
		command.add(jvmClasspath());
		command.add(TrainingLauncher.class.getName());
		command.addAll(programArgs);

		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		int exitCode;
		try {
			exitCode = process.waitFor();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for training run", ex);
		}
		return new ProcessResult(exitCode, output);
	}

	private String jvmClasspath() {
		String classesJar = System.getProperty("aot.test.classesJar");
		String coreJar = System.getProperty("aot.test.coreJar");
		String libraries = System.getProperty("aot.test.runtimeClasspath", "");
		if (classesJar == null || coreJar == null) {
			throw new IllegalStateException("aot.test.classesJar / aot.test.coreJar system properties are not set");
		}
		StringBuilder classpath = new StringBuilder(classesJar).append(File.pathSeparator).append(coreJar);
		if (!libraries.isEmpty()) {
			classpath.append(File.pathSeparator).append(libraries);
		}
		return classpath.toString();
	}

	record ProcessResult(int exitCode, String output) {
	}

}