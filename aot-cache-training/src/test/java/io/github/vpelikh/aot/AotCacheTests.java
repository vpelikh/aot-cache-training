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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AotCache}.
 *
 * @author Vasily Pelikh
 */
class AotCacheTests {

    @Test
    void isRecordingEnabledWhenOutputFlagPresent() {
        assertThat(AotCache.isRecordingEnabled(List.of("-Xmx512m", "-XX:AOTCacheOutput=build/app.aot"))).isTrue();
    }

    @Test
    void isRecordingEnabledWhenOutputFlagAbsent() {
        assertThat(AotCache.isRecordingEnabled(List.of("-Xmx512m", "-jar", "app.jar"))).isFalse();
    }

    @Test
    void isRecordingEnabledIgnoresTwoStepRecordMode() {
        // JDK 24 two-step record mode is not the single-step workflow we support
        assertThat(AotCache
            .isRecordingEnabled(List.of("-XX:AOTMode=record", "-XX:AOTConfiguration=build/app.aotconf"))).isFalse();
    }

    @Test
    void isRecordingEnabledIgnoresCreateMode() {
        assertThat(AotCache.isRecordingEnabled(List.of("-XX:AOTMode=create", "-XX:AOTCache=build/app.aot"))).isFalse();
    }

    @Test
    void isRecordingEnabledIgnoresEmptyOutputPath() {
        assertThat(AotCache.isRecordingEnabled(List.of("-XX:AOTCacheOutput="))).isFalse();
    }

    @Test
    void findOutputPathReturnsConfiguredPath() {
        assertThat(AotCache.findOutputPath(List.of("-Xmx512m", "-XX:AOTCacheOutput=build/app.aot")))
            .isEqualTo("build/app.aot");
    }

    @Test
    void findOutputPathReturnsNullWhenAbsent() {
        assertThat(AotCache.findOutputPath(List.of("-Xmx512m"))).isNull();
    }

    @Test
    void defaultCacheFileResolvesConventionalPath(@TempDir Path buildOutput) {
        assertThat(AotCache.defaultCacheFile(buildOutput))
            .isEqualTo(buildOutput.resolve("aot-cache").resolve("application.aot"));
    }

    @Test
    void recordingArgumentIsAbsolute(@TempDir Path buildOutput) {
        assertThat(AotCache.recordingArgument(buildOutput.resolve("app.aot")))
            .isEqualTo("-XX:AOTCacheOutput=" + buildOutput.resolve("app.aot").toAbsolutePath());
    }

    @Test
    void verifyRecordedCacheReturnsSizeForNonEmptyFile(@TempDir Path tempDir) throws Exception {
        Path cacheFile = tempDir.resolve("application.aot");
        Files.writeString(cacheFile, "cache-bytes");
        assertThat(AotCache.verifyRecordedCache(cacheFile)).isEqualTo("cache-bytes".length());
    }

    @Test
    void verifyRecordedCacheReturnsNegativeForEmptyFile(@TempDir Path tempDir) throws Exception {
        Path cacheFile = tempDir.resolve("application.aot");
        Files.createFile(cacheFile);
        assertThat(AotCache.verifyRecordedCache(cacheFile)).isEqualTo(0);
    }

    @Test
    void verifyRecordedCacheReturnsNegativeForMissingFile(@TempDir Path tempDir) {
        assertThat(AotCache.verifyRecordedCache(tempDir.resolve("missing.aot"))).isNegative();
    }

    @Test
    void embedCacheIntoJarAddsCacheWithReadableUnixMode(@TempDir Path tempDir) throws Exception {
        Path bootJar = tempDir.resolve("app.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(bootJar))) {
            out.putNextEntry(new JarEntry("BOOT-INF/classes/app.txt"));
            out.write("hello".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Path cache = tempDir.resolve("application.aot");
        Files.writeString(cache, "cache-bytes");
        Path output = tempDir.resolve("out/app.jar");

        AotCache.embedCacheIntoJar(bootJar, cache, output);

        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertThat(zip.getEntry("BOOT-INF/classes/app.txt")).as("existing entry preserved").isNotNull();
            ZipEntry entry = zip.getEntry("aot-cache/application.aot");
            assertThat(entry).isNotNull();
            assertThat(new String(zip.getInputStream(entry).readAllBytes())).isEqualTo("cache-bytes");
            assertThat(zip.getEntry("aot-cache/")).isNotNull();
        }
        assertThat(unixMode(output, "aot-cache/application.aot")).isEqualTo("100644");
        assertThat(unixMode(output, "aot-cache/")).isEqualTo("40755");
    }

    @Test
    void embedCacheIntoJarReplacesAnExistingCacheEntry(@TempDir Path tempDir) throws Exception {
        Path bootJar = tempDir.resolve("app.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(bootJar))) {
            out.putNextEntry(new JarEntry("aot-cache/application.aot"));
            out.write("stale".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Path cache = tempDir.resolve("application.aot");
        Files.writeString(cache, "fresh");
        Path output = tempDir.resolve("out/app.jar");

        AotCache.embedCacheIntoJar(bootJar, cache, output);

        try (ZipFile zip = new ZipFile(output.toFile())) {
            long count = zip.stream().filter((entry) -> entry.getName().equals("aot-cache/application.aot")).count();
            assertThat(count).as("no duplicate cache entry").isEqualTo(1);
            assertThat(new String(zip.getInputStream(zip.getEntry("aot-cache/application.aot")).readAllBytes()))
                .isEqualTo("fresh");
        }
    }

    private static String unixMode(Path jar, String name) throws Exception {
        byte[] bytes = Files.readAllBytes(jar);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset + 46 <= bytes.length; offset++) {
            if (Integer.toUnsignedLong(buffer.getInt(offset)) != 0x02014b50L) {
                continue;
            }
            int nameLength = Short.toUnsignedInt(buffer.getShort(offset + 28));
            String candidate = new String(bytes, offset + 46, nameLength, StandardCharsets.UTF_8);
            if (candidate.equals(name)) {
                return Integer.toOctalString(buffer.getInt(offset + 38) >>> 16);
            }
        }
        throw new IllegalStateException("Entry not found: " + name);
    }

}
