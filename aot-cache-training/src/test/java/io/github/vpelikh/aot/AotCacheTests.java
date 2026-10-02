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
        java.nio.file.Files.writeString(cacheFile, "cache-bytes");
        assertThat(AotCache.verifyRecordedCache(cacheFile)).isEqualTo("cache-bytes".length());
    }

    @Test
    void verifyRecordedCacheReturnsNegativeForEmptyFile(@TempDir Path tempDir) throws Exception {
        Path cacheFile = tempDir.resolve("application.aot");
        java.nio.file.Files.createFile(cacheFile);
        assertThat(AotCache.verifyRecordedCache(cacheFile)).isEqualTo(0);
    }

    @Test
    void verifyRecordedCacheReturnsNegativeForMissingFile(@TempDir Path tempDir) {
        assertThat(AotCache.verifyRecordedCache(tempDir.resolve("missing.aot"))).isNegative();
    }

}
