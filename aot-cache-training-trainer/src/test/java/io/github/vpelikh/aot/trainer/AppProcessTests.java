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
    void startAcceptsRelativeCachePath(@TempDir Path tempDir) {
        // A relative cache path resolves against the working directory, so its parent exists;
        // start() must handle it without erroring on the directory step.
        AppProcess process = process(tempDir.resolve("missing.jar"), tempDir.resolve("layout"),
                Path.of("application.aot"));

        assertThatIOException().isThrownBy(() -> process.start(URI.create("http://localhost:8080/")));
    }

    @Test
    void startAcceptsCachePathAtAFileSystemRoot(@TempDir Path tempDir) {
        // A filesystem root is the only path whose parent is null. start() must skip creating
        // that (null) parent rather than throw a NullPointerException; it still fails later,
        // because the application JAR does not exist.
        AppProcess process = process(tempDir.resolve("missing.jar"), tempDir.resolve("layout"), Path.of("/"));

        assertThatIOException().isThrownBy(() -> process.start(URI.create("http://localhost:8080/")));
    }

    @Test
    void containerIdIsTakenFromTheLastLineWhenTheImageHadToBePulled() {
        // When the image is not present, `docker run -d` prints pull progress before the id;
        // the container id is the last line. Taking the first line would target "Unable to
        // find image ... locally" and leak the container.
        String output = """
                Unable to find image 'eclipse-temurin:25' locally
                25: Pulling from library/eclipse-temurin
                Digest: sha256:abc123
                Status: Downloaded newer image for eclipse-temurin:25
                84e30ef2982cd68345ff897349831e2f0ae6fccb896628d18d96f9c110450e42
                """;
        assertThat(AppProcess.containerIdFromRunOutput(output))
            .isEqualTo("84e30ef2982cd68345ff897349831e2f0ae6fccb896628d18d96f9c110450e42");
    }

    @Test
    void containerIdIsTakenFromTheOnlyLineWhenTheImageIsAlreadyPresent() {
        String output = "84e30ef2982cd68345ff897349831e2f0ae6fccb896628d18d96f9c110450e42\n";
        assertThat(AppProcess.containerIdFromRunOutput(output))
            .isEqualTo("84e30ef2982cd68345ff897349831e2f0ae6fccb896628d18d96f9c110450e42");
    }

    @Test
    void containerIdIsEmptyWhenNoOutputIsProduced() {
        assertThat(AppProcess.containerIdFromRunOutput("")).isEmpty();
        assertThat(AppProcess.containerIdFromRunOutput("\n  \n")).isEmpty();
    }
}
