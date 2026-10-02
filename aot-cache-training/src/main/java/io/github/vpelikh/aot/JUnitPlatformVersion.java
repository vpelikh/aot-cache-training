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
import java.util.Map;
import java.util.Optional;

/**
 * Detects the JUnit Platform generation already present in a project's test dependencies.
 *
 * <p>The training class path must use the same JUnit Platform generation as the project.
 * Otherwise the launcher API and the test engine disagree (for example, a JUnit 6 engine
 * with a JUnit 5 launcher fails with "versions of JUnit jars ... not being properly
 * aligned"). The build plugins use this to resolve a matching launcher instead of shipping
 * their own.
 *
 * <p>Detection prefers build-tool dependency coordinates ({@value #COMMONS_COORDINATE},
 * {@value #ENGINE_COORDINATE}) and only falls back to JAR file names, so it also works when
 * the project's classes come from directories and no JUnit Platform JAR is on disk.
 *
 * <p>This class is dependency-free so it can be used by build tooling.
 *
 * @author Vasily Pelikh
 */
public final class JUnitPlatformVersion {

	/**
	 * The coordinates of the JUnit Platform Commons module, the most reliable marker.
	 */
	public static final String COMMONS_COORDINATE = "org.junit.platform:junit-platform-commons";

	/**
	 * The coordinates of the JUnit Platform Engine module.
	 */
	public static final String ENGINE_COORDINATE = "org.junit.platform:junit-platform-engine";

	/**
	 * The coordinates of the JUnit Platform Launcher module.
	 */
	public static final String LAUNCHER_COORDINATE = "org.junit.platform:junit-platform-launcher";

	private static final String[] COORDINATE_MARKERS = { COMMONS_COORDINATE, ENGINE_COORDINATE, LAUNCHER_COORDINATE };

	private static final String[] FILE_NAME_MARKERS = { "junit-platform-commons-", "junit-platform-engine-",
			"junit-platform-launcher-" };

	private JUnitPlatformVersion() {
	}

	/**
	 * Find the JUnit Platform version from resolved dependency coordinates.
	 * @param coordinates a map from {@code group:artifact} to version
	 * @return the JUnit Platform version, for example {@code 6.0.3}, or empty if none is found
	 */
	public static Optional<String> fromCoordinates(Map<String, String> coordinates) {
		for (String marker : COORDINATE_MARKERS) {
			String version = coordinates.get(marker);
			if (isVersion(version)) {
				return Optional.of(version);
			}
		}
		return Optional.empty();
	}

	/**
	 * Find the JUnit Platform version on the given class path by inspecting JUnit Platform
	 * JAR file names. Use {@link #fromCoordinates(Map)} first when coordinates are available.
	 * @param classpathElements the class path elements (files or directories)
	 * @return the JUnit Platform version, or empty if none is found
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
		for (String marker : FILE_NAME_MARKERS) {
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
		if (value == null || value.isEmpty()) {
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