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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Utilities for arranging a JVM AOT cache training run.
 *
 * <p>The JVM refuses to record a cache when any class path entry is a non-empty directory
 * ({@code Cannot have non-empty directory in paths}). Build tools normally expose the
 * compiled application and test classes as directories, so a training run must first
 * package them into JARs and drop the directories from the class path.
 *
 * @author Vasily Pelikh
 */
public final class TrainingClasspath {

	private TrainingClasspath() {
	}

	/**
	 * Package the contents of a directory into a JAR file.
	 * @param sourceDirectory the directory to package (may not exist)
	 * @param jarFile the JAR file to create
	 * @throws IOException if packaging fails
	 */
	public static void jarDirectory(Path sourceDirectory, Path jarFile) throws IOException {
		Files.createDirectories(jarFile.getParent());
		try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarFile))) {
			if (Files.isDirectory(sourceDirectory)) {
				try (var stream = Files.walk(sourceDirectory)) {
					List<Path> files = stream.filter(Files::isRegularFile).sorted().toList();
					for (Path file : files) {
						String entryName = sourceDirectory.relativize(file).toString().replace('\\', '/');
						jar.putNextEntry(new JarEntry(entryName));
						Files.copy(file, jar);
						jar.closeEntry();
					}
				}
			}
		}
	}

	/**
	 * Replace any directory class path element with a JAR, producing a JAR-only class path.
	 *
	 * <p>Directory elements are packaged into JARs under {@code workDirectory}; existing
	 * JAR and other file elements are kept as-is (except empty directories, which the JVM
	 * tolerates, so they are kept too).
	 * @param classpathElements the raw class path elements
	 * @param workDirectory the directory to hold generated JARs
	 * @return a class path string containing no non-empty directory
	 * @throws IOException if packaging fails
	 */
	public static String jarOnlyClasspath(List<Path> classpathElements, Path workDirectory) throws IOException {
		List<String> entries = new ArrayList<>();
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		int index = 0;
		for (Path element : classpathElements) {
			if (element == null) {
				continue;
			}
			Path normalized = element.toAbsolutePath().normalize();
			Path resolved = normalized;
			if (Files.isDirectory(normalized)) {
				long fileCount;
				try (var stream = Files.list(normalized)) {
					fileCount = stream.count();
				}
				if (fileCount > 0) {
					Path jar = workDirectory.resolve("classpath-" + (index++) + ".jar");
					jarDirectory(normalized, jar);
					resolved = jar;
				}
			}
			String absolute = resolved.toAbsolutePath().normalize().toString();
			if (seen.add(absolute)) {
				entries.add(absolute);
			}
		}
		return String.join(java.io.File.pathSeparator, entries);
	}

}