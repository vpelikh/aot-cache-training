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

package io.github.vpelikh.aot.it;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import io.github.vpelikh.aot.AotCache;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test that records a real JVM AOT cache from a Spring integration-test run.
 *
 * <p>It forks a JVM with the JDK 25 {@code -XX:AOTCacheOutput} flag, executes
 * {@link TrainingRunMain} (which drives the Spring TestContext framework so that
 * {@code AotCacheTestExecutionListener} participates), and then asserts that:
 * <ol>
 * <li>the cache file is produced;</li>
 * <li>a second JVM can load that cache with {@code -XX:AOTCache} without rejecting it,
 * which is the JVM's own compatibility check.</li>
 * </ol>
 *
 * <p>Only runs on JDK 25+.
 *
 * @author Vasily Pelikh
 */
@EnabledIf("io.github.vpelikh.aot.it.AotCacheRecordingIntegrationTests#jdkSupportsRecording")
class AotCacheRecordingIntegrationTests {

    static boolean jdkSupportsRecording() {
        return Runtime.version().feature() >= 25;
    }

    @Test
    void recordsAndReusesCacheFromSpringIntegrationTests(@TempDir Path tempDir) throws Exception {
        Path cacheFile = tempDir.resolve("aot-cache").resolve("application.aot");
        Files.createDirectories(cacheFile.getParent());

        // Phase 1: training run records the cache.
        ProcessResult recording = runJvm(List.of("-XX:AOTCacheOutput=" + cacheFile), TrainingRunMain.class.getName(),
                List.of());
        assertThat(recording.exitCode()).as("training run exit code; output:%n%s", recording.output()).isZero();
        assertThat(cacheFile).as("cache file after training run; output:%n%s", recording.output()).exists();
        long cacheSize = AotCache.verifyRecordedCache(cacheFile);
        assertThat(cacheSize).as("recorded cache should be non-empty").isGreaterThan(0);

        // Phase 2: a fresh JVM must accept (load) the cache. The JVM rejects caches that
        // do not match the runtime, so a successful run proves compatibility.
        ProcessResult reuse = runJvm(List.of("-XX:AOTCache=" + cacheFile, "-XX:AOTMode=on"),
                TrainingRunMain.class.getName(), List.of());
        assertThat(reuse.exitCode()).as("reuse run exit code; output:%n%s", reuse.output()).isZero();
        assertThat(reuse.output()).doesNotContain("WARNING: AOT cache could not be loaded");
    }

    private ProcessResult runJvm(List<String> jvmArgs, String mainClass, List<String> programArgs) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(jvmClasspath());
        command.add(mainClass);
        command.addAll(programArgs);

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode;
        try {
            exitCode = process.waitFor();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for training run", ex);
        }
        return new ProcessResult(exitCode, output);
    }

    /**
     * Build a JAR-only classpath for the training JVM.
     *
     * <p>The JVM only records classes loaded from JARs, so the training classes are
     * packaged into a JAR by the build and prepended here. Mocking helpers (Mockito and
     * Byte Buddy) are excluded: their self-attaching agents install class-file transformers
     * that make the JVM's cache-assembly step fail.
     */
    private String jvmClasspath() {
        String classesJar = System.getProperty("aot.test.classesJar");
        String libraries = System.getProperty("aot.test.runtimeClasspath", "");
        if (classesJar == null) {
            throw new IllegalStateException("aot.test.classesJar system property is not set");
        }
        String filtered = java.util.Arrays.stream(libraries.split(java.io.File.pathSeparator))
            .filter((entry) -> !isMockingLibrary(entry))
            .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        return filtered.isEmpty() ? classesJar : classesJar + java.io.File.pathSeparator + filtered;
    }

    private boolean isMockingLibrary(String classpathEntry) {
        String name = Path.of(classpathEntry).getFileName().toString();
        return name.startsWith("mockito-") || name.startsWith("byte-buddy") || name.startsWith("objenesis-");
    }

    record ProcessResult(int exitCode, String output) {
    }

}
