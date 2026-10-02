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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link TrainingClasspath}.
 *
 * @author Vasily Pelikh
 */
class TrainingClasspathTests {

    @Test
    void jarDirectoryWritesFileAndDirectoryEntries(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("classes");
        Files.createDirectories(source.resolve("example"));
        Files.writeString(source.resolve("example/Example.class"), "app");
        Files.createDirectories(source.resolve("example/nested"));
        Files.writeString(source.resolve("example/nested/Helper.class"), "helper");
        Path jar = tempDir.resolve("classpath-0.jar");

        TrainingClasspath.jarDirectory(source, jar);

        List<String> entries = new ArrayList<>();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            jarFile.stream().map(JarEntry::getName).forEach(entries::add);
        }
        assertThat(entries).contains("example/Example.class", "example/nested/Helper.class");
        // Directory entries are required for classpath scanning (e.g. Spring's classpath*:).
        assertThat(entries).contains("example/", "example/nested/");
        assertThat(entries.stream().filter((entry) -> entry.equals("example/")).count()).isEqualTo(1);
    }

    @Test
    void jarDirectoryToleratesMissingSource(@TempDir Path tempDir) throws Exception {
        Path jar = tempDir.resolve("empty.jar");
        TrainingClasspath.jarDirectory(tempDir.resolve("does-not-exist"), jar);
        assertThat(jar).exists();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            assertThat(jarFile.stream()).isEmpty();
        }
    }

}
