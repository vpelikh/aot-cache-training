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

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

/**
 * Tests for {@link AppProcess}.
 *
 * @author Vasily Pelikh
 */
class AppProcessTests {

    private AppProcess process(Path appJar, Path layoutDirectory, Path cacheFile) {
        return new AppProcess(appJar, layoutDirectory, cacheFile, "java", "sample.App",
                List.of(), Duration.ofSeconds(5), null, "docker");
    }

    @Test
    void stopIsSafeBeforeStart(@TempDir Path tempDir) {
        // The launcher calls stop() from a finally block even when start() failed, so stop()
        // must not throw when no process was ever started.
        AppProcess process = process(tempDir.resolve("app.jar"), tempDir.resolve("layout"),
                tempDir.resolve("cache/application.aot"));

        assertThat(process.stop()).isFalse();
    }

    @Test
    void startFailsWhenTheApplicationJarDoesNotExist(@TempDir Path tempDir) {
        AppProcess process = process(tempDir.resolve("missing.jar"), tempDir.resolve("layout"),
                tempDir.resolve("cache/application.aot"));

        assertThatIOException().isThrownBy(() -> process.start(URI.create("http://localhost:8080/")));
    }

    @Test
    void cachePathWithoutParentIsAccepted(@TempDir Path tempDir) {
        // A bare relative cache path has no parent; start() must not NPE while creating the
        // (absent) parent directory.
        AppProcess process = process(tempDir.resolve("missing.jar"), tempDir.resolve("layout"),
                Path.of("application.aot"));

        assertThatIOException().isThrownBy(() -> process.start(URI.create("http://localhost:8080/")));
    }

}