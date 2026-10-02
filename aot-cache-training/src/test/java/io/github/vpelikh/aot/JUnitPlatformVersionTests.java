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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link JUnitPlatformVersion}.
 *
 * @author Vasily Pelikh
 */
class JUnitPlatformVersionTests {

	@Test
	void fromCommonsJar() {
		assertThat(JUnitPlatformVersion.fromFileName("junit-platform-commons-6.0.3.jar")).contains("6.0.3");
	}

	@Test
	void fromCoordinatesPrefersCommons() {
		java.util.Map<String, String> coordinates = java.util.Map.of(
				"org.junit.jupiter:junit-jupiter", "6.1.3",
				JUnitPlatformVersion.ENGINE_COORDINATE, "6.1.3",
				JUnitPlatformVersion.COMMONS_COORDINATE, "6.1.3");
		assertThat(JUnitPlatformVersion.fromCoordinates(coordinates)).contains("6.1.3");
	}

	@Test
	void fromCoordinatesIgnoresUnrelatedModules() {
		java.util.Map<String, String> coordinates = java.util.Map.of("org.junit.jupiter:junit-jupiter", "6.1.3",
				"org.springframework:spring-test", "7.0.9");
		assertThat(JUnitPlatformVersion.fromCoordinates(coordinates)).isEmpty();
	}

	@Test
	void fromCoordinatesRejectsClassifierLikeValues() {
		java.util.Map<String, String> coordinates = java.util.Map.of(JUnitPlatformVersion.COMMONS_COORDINATE,
				"6.0.3-sources");
		assertThat(JUnitPlatformVersion.fromCoordinates(coordinates)).isEmpty();
	}

	@Test
	void defaultPlatformVersionMatchesTheVersionCatalog() {
		String catalogVersion = System.getProperty("aot.test.junitPlatformVersion");
		assertThat(catalogVersion).as("the test build must pass the catalog version").isNotNull();
		assertThat(JUnitPlatformVersion.DEFAULT_PLATFORM_VERSION)
			.as("JUnitPlatformVersion.DEFAULT_PLATFORM_VERSION must match the version catalog")
			.isEqualTo(catalogVersion);
	}

	@Test
	void fromEngineJar() {
		assertThat(JUnitPlatformVersion.fromFileName("junit-platform-engine-1.12.2.jar")).contains("1.12.2");
	}

	@Test
	void fromLauncherJar() {
		assertThat(JUnitPlatformVersion.fromFileName("junit-platform-launcher-6.1.3.jar")).contains("6.1.3");
	}

	@Test
	void ignoresNonPlatformJars() {
		assertThat(JUnitPlatformVersion.fromFileName("junit-jupiter-api-6.0.3.jar")).isEmpty();
		assertThat(JUnitPlatformVersion.fromFileName("spring-core-7.0.9.jar")).isEmpty();
		assertThat(JUnitPlatformVersion.fromFileName("junit-platform-commons-6.0.3-sources.jar")).isEmpty();
	}

	@Test
	void findScansClasspathElements() {
		List<Path> classpath = List.of(Path.of("/repo/spring-core-7.0.9.jar"),
				Path.of("/repo/junit-platform-commons-6.0.3.jar"), Path.of("/repo/junit-jupiter-api-6.0.3.jar"));
		assertThat(JUnitPlatformVersion.find(classpath)).contains("6.0.3");
	}

	@Test
	void findReturnsEmptyWhenAbsent() {
		assertThat(JUnitPlatformVersion.find(List.of(Path.of("/repo/spring-core-7.0.9.jar")))).isEmpty();
	}

}