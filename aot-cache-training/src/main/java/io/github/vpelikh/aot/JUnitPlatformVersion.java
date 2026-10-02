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

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Detects the JUnit Platform generation already present on a project's test class path.
 *
 * <p>The training class path must use the same JUnit Platform generation as the project.
 * Otherwise the launcher API and the test engine disagree (for example, a JUnit 6 engine
 * with a JUnit 5 launcher fails with "versions of JUnit jars ... not being properly
 * aligned"). This utility lets the build plugins resolve a matching launcher instead of
 * shipping their own.
 *
 * <p>This class is dependency-free so it can be used by build tooling.
 *
 * @author Vasily Pelikh
 */
public final class JUnitPlatformVersion {

	private static final String[] MARKERS = { "junit-platform-commons-", "junit-platform-engine-",
			"junit-platform-launcher-" };

	private JUnitPlatformVersion() {
	}

	/**
	 * Find the JUnit Platform version on the given class path by inspecting JUnit Platform
	 * JAR file names.
	 * @param classpathElements the class path elements (files or directories)
	 * @return the JUnit Platform version, for example {@code 6.0.3}, or empty if none is found
	 */
	public static Optional<String> find(List<Path> classpathElements) {
		for (Path element : classpathElements) {
			if (element == null) {
				continue;
			}
			Optional<String> version = fromFileName(element.getFileName().toString());
			if (version.isPresent()) {
				return version;
			}
		}
		return Optional.empty();
	}

	/**
	 * Extract the JUnit Platform version from a JUnit Platform JAR file name.
	 * @param fileName a file name such as {@code junit-platform-commons-6.0.3.jar}
	 * @return the version, or empty if the file name is not a JUnit Platform JAR
	 */
	public static Optional<String> fromFileName(String fileName) {
		for (String marker : MARKERS) {
			if (fileName.startsWith(marker) && fileName.endsWith(".jar")) {
				String version = fileName.substring(marker.length(), fileName.length() - ".jar".length());
				if (isVersion(version)) {
					return Optional.of(version);
				}
			}
		}
		return Optional.empty();
	}

	private static boolean isVersion(String value) {
		if (value.isEmpty()) {
			return false;
		}
		for (int i = 0; i < value.length(); i++) {
			char ch = value.charAt(i);
			if (!Character.isDigit(ch) && ch != '.') {
				// Reject classifiers such as -sources, -javadoc.
				return false;
			}
		}
		return true;
	}

}