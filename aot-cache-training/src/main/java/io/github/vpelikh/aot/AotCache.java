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

package io.github.vpelikh.aot;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Central knowledge about the JVM AOT cache (JEP 483 / JEP 514) as recorded during a
 * training run.
 *
 * <p>This class is deliberately free of any Spring dependency so it can be used by build
 * tooling and by the test-time listener alike.
 *
 * <p>The single-step recording workflow introduced in JDK 25 (JEP 514) is a JVM
 * command-line flag:
 *
 * <pre>{@code
 * java -XX:AOTCacheOutput=build/aot-cache/application.aot -jar app.jar
 * }</pre>
 *
 * <p>The JVM records a temporary AOT configuration during the run and, on clean exit,
 * assembles the final cache at the given path. The flag can only be provided at JVM
 * startup, which is why recording is driven by test/build tooling rather than by a
 * library at runtime.
 *
 * @author Vasily Pelikh
 * @see <a href="https://openjdk.org/jeps/483">JEP 483: Ahead-of-Time Class Loading &amp; Linking</a>
 * @see <a href="https://openjdk.org/jeps/514">JEP 514: Ahead-of-Time Command-Line Ergonomics</a>
 */
public final class AotCache {

	/**
	 * The system property / environment variable used to opt in to AOT cache recording
	 * when the flag is injected by tooling. Its value is not otherwise interpreted: the
	 * effective switch is the presence of {@value #OUTPUT_FLAG} on the JVM command line.
	 */
	public static final String ENABLED_PROPERTY = "aot.cache.recording.enabled";

	/**
	 * The JVM flag that enables single-step AOT cache recording.
	 */
	public static final String OUTPUT_FLAG = "-XX:AOTCacheOutput=";

	/**
	 * The JVM flag that loads a pre-recorded cache at startup.
	 */
	public static final String CACHE_FLAG = "-XX:AOTCache=";

	/**
	 * The minimum JDK feature version that supports single-step recording (JDK 25, JEP 514).
	 */
	public static final int MINIMUM_RECORDING_JDK = 25;

	/**
	 * The minimum JDK feature version that supports loading an AOT cache (JDK 24, JEP 483).
	 */
	public static final int MINIMUM_LOADING_JDK = 24;

	/**
	 * The conventional file name of a recorded cache within an {@code aot-cache} directory.
	 */
	public static final String CACHE_FILE_NAME = "application.aot";

	/**
	 * The conventional directory that holds a recorded cache, relative to the build
	 * output or application content.
	 */
	public static final String CACHE_DIRECTORY = "aot-cache";

	/**
	 * Environment variable understood by the Paketo Spring Boot buildpack to opt in to
	 * AOT cache handling.
	 */
	public static final String BUILDPACK_ENABLE_ENV = "BP_JVM_AOTCACHE_ENABLED";

	private static final String INPUT_ARGUMENT_PREFIX = "-XX:";

	private AotCache() {
	}

	/**
	 * Return {@code true} if the given JVM input arguments enable single-step AOT cache
	 * recording.
	 * @param jvmArguments the JVM command-line arguments
	 * @return {@code true} if {@value #OUTPUT_FLAG} is present
	 */
	public static boolean isRecordingEnabled(List<String> jvmArguments) {
		return findOutputPath(jvmArguments) != null;
	}

	/**
	 * Find the AOT cache output path configured on the given JVM input arguments.
	 * @param jvmArguments the JVM command-line arguments
	 * @return the configured output path, or {@code null} if recording is not enabled
	 */
	public static @Nullable String findOutputPath(List<String> jvmArguments) {
		for (String argument : jvmArguments) {
			if (argument.startsWith(OUTPUT_FLAG)) {
				String value = argument.substring(OUTPUT_FLAG.length()).trim();
				if (!value.isEmpty()) {
					return value;
				}
			}
		}
		return null;
	}

	/**
	 * Return the JVM input arguments of the current process, excluding arguments passed
	 * to the main method.
	 * @return the current JVM input arguments
	 */
	public static List<String> currentJvmArguments() {
		return ManagementFactory.getRuntimeMXBean().getInputArguments();
	}

	/**
	 * Return {@code true} if the running JDK is new enough to record an AOT cache.
	 * @return {@code true} if the current feature version is {@value #MINIMUM_RECORDING_JDK}
	 * or later
	 */
	public static boolean isCurrentJdkSupported() {
		return Runtime.version().feature() >= MINIMUM_RECORDING_JDK;
	}

	/**
	 * Resolve the conventional cache file path within the given build output directory.
	 * @param buildOutputDirectory the build output directory (for example {@code build/}
	 * or {@code target/})
	 * @return the path to {@code <buildOutputDirectory>/aot-cache/application.aot}
	 */
	public static Path defaultCacheFile(Path buildOutputDirectory) {
		return buildOutputDirectory.resolve(CACHE_DIRECTORY).resolve(CACHE_FILE_NAME);
	}

	/**
	 * Build the JVM argument that enables recording to the given path.
	 * @param outputPath the cache output path
	 * @return the {@value #OUTPUT_FLAG} argument
	 */
	public static String recordingArgument(Path outputPath) {
		return OUTPUT_FLAG + outputPath.toAbsolutePath();
	}

	/**
	 * Return a minimal map of runtime properties that identify the JDK and platform that
	 * recorded a cache. This is informational only: the JVM remains the source of truth
	 * for cache compatibility.
	 * @return metadata such as {@code java.version} and {@code os.arch}
	 */
	public static Map<String, String> runtimeMetadata() {
		return Map.of("java.version", System.getProperty("java.version", ""), "java.vendor",
				System.getProperty("java.vendor", ""), "os.name", System.getProperty("os.name", ""), "os.arch",
				System.getProperty("os.arch", ""));
	}

	/**
	 * Return {@code true} if the given argument is a JVM {@code -XX:} option, which can be
	 * useful when appending to a user-provided argument list.
	 * @param argument the argument to check
	 * @return {@code true} if the argument starts with {@value #INPUT_ARGUMENT_PREFIX}
	 */
	public static boolean isJvmOption(String argument) {
		return argument.startsWith(INPUT_ARGUMENT_PREFIX);
	}

	/**
	 * Verify that a cache was recorded at the given path.
	 *
	 * <p>This must be called by build tooling <em>after</em> the test JVM has exited: the
	 * JVM assembles the final cache only after shutdown hooks have run, so a check from
	 * within the test JVM (for example, a shutdown hook) would always report a missing
	 * cache.
	 * @param cacheFile the expected cache file
	 * @return the size of the recorded cache in bytes, or {@code -1} if it was not recorded
	 */
	public static long verifyRecordedCache(Path cacheFile) {
		if (Files.isRegularFile(cacheFile)) {
			try {
				return Files.size(cacheFile);
			}
			catch (IOException ex) {
				return -1;
			}
		}
		return -1;
	}

}