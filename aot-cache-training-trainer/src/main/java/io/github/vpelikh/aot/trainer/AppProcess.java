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
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.vpelikh.aot.AotCache;

/**
 * The packaged application started in its own JVM while recording a JVM AOT cache.
 *
 * <p>An AOT cache only loads against the exact class path it was recorded with, and the
 * class path of the packaged application ({@code runner.jar} with its {@code lib/}
 * manifest entries) differs from a test class path. Recording from the application's own
 * JVM therefore lets the recorded class path <em>be</em> the runtime class path, so the
 * cache is usable by the packaged application. Integration tests drive this process as an
 * external HTTP client, exactly as {@code @QuarkusIntegrationTest} does.
 *
 * <p>The application JAR is extracted with {@code -Djarmode=tools ... extract} so every
 * class path entry is a JAR, then started with {@code -XX:AOTCacheOutput=<cache>}. Only
 * {@code runner.jar} is placed on the class path: its {@code Class-Path} manifest entries
 * supply {@code lib/}, which keeps the recorded class path a prefix of the buildpack's
 * class path.
 *
 * <p>The recording JVM is either the local {@code java} executable or, when a container
 * image is configured, the JVM inside that image. A cache is only loaded by the exact JVM
 * build and architecture that recorded it, so recording in the target image is what makes
 * the cache usable inside that image.
 *
 * @author Vasily Pelikh
 */
public final class AppProcess {

    /** The buildpack normalizes extracted application files to this instant before loading a cache. */
    private static final FileTime NORMALIZED_TIMESTAMP = FileTime.fromMillis(315532801000L);

    private static final Duration EXTRACT_TIMEOUT = Duration.ofSeconds(120);

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(60);

    /** Where the application layout and the cache are mounted inside the container. */
    private static final String CONTAINER_APP_DIR = "/aot-cache-training/app";

    private static final String CONTAINER_CACHE_DIR = "/aot-cache-training/cache";

    private final Path appJar;

    private final Path layoutDirectory;

    private final Path cacheFile;

    private final String javaExecutable;

    private final String startClass;

    private final List<String> applicationArguments;

    private final Duration startTimeout;

    private final String containerImage;

    private final String containerRuntime;

    private Process process;

    private String containerName;

    /**
     * Create a harness.
     * @param appJar the packaged Spring Boot application JAR
     * @param layoutDirectory a working directory to extract the application into
     * @param cacheFile the AOT cache output path
     * @param javaExecutable the local {@code java} executable (used when no container image
     * is configured)
     * @param startClass the application start class (from the JAR manifest)
     * @param applicationArguments arguments passed to the application
     * @param startTimeout how long to wait for the application to become ready
     * @param containerImage the container image whose JVM records the cache, or {@code null}
     * to use {@code javaExecutable}
     * @param containerRuntime the container runtime executable (for example {@code docker})
     */
    public AppProcess(Path appJar, Path layoutDirectory, Path cacheFile, String javaExecutable, String startClass,
            List<String> applicationArguments, Duration startTimeout, String containerImage,
            String containerRuntime) {
        this.appJar = appJar;
        this.layoutDirectory = layoutDirectory;
        this.cacheFile = cacheFile;
        this.javaExecutable = javaExecutable;
        this.startClass = startClass;
        this.applicationArguments = List.copyOf(applicationArguments);
        this.startTimeout = startTimeout;
        this.containerImage = containerImage;
        this.containerRuntime = containerRuntime;
    }

    /**
     * Extract the application and start it with {@code -XX:AOTCacheOutput}.
     * @param readyCheck a URL that returns a 2xx/3xx response once the application is ready
     * @throws IOException if the application cannot be extracted or started
     */
    public void start(URI readyCheck) throws IOException {
        Files.createDirectories(this.layoutDirectory);
        Files.createDirectories(this.cacheFile.toAbsolutePath().getParent());
        extract();
        begin(buildCommand(readyCheck), readyCheck);
    }

    /**
     * Stop the application gracefully so the JVM assembles the cache on clean exit.
     * @return {@code true} if the process exited on its own after the stop request
     */
    public boolean stop() {
        if (this.containerName != null) {
            return stopContainer();
        }
        if (this.process == null) {
            return false;
        }
        this.process.destroy();
        try {
            if (this.process.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                return true;
            }
            this.process.destroyForcibly();
            return this.process.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            this.process.destroyForcibly();
            return false;
        }
    }

    /**
     * Stop the container with {@code <runtime> stop}, which sends SIGTERM to the
     * application process inside it. The container is run detached precisely so this is
     * possible: killing an attached {@code docker run} client instead terminates the
     * container abruptly, and the JVM never assembles the cache.
     */
    private boolean stopContainer() {
        if (!run(List.of(this.containerRuntime, "stop", "--timeout", Long.toString(STOP_TIMEOUT.toSeconds()),
                this.containerName))) {
            run(List.of(this.containerRuntime, "rm", "-f", this.containerName));
            return false;
        }
        // `docker stop` waits for the container to exit, which happens once the JVM has run
        // its shutdown hooks and assembled the cache. The container's own exit status is 143
        // (128 + SIGTERM) by design, so success is judged by the stop command, not that code.
        run(List.of(this.containerRuntime, "wait", this.containerName));
        run(List.of(this.containerRuntime, "rm", "-f", this.containerName));
        return true;
    }

    private boolean run(List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(logFile("container.log").toFile());
            Process runner = builder.start();
            return runner.waitFor(STOP_TIMEOUT.toSeconds() + 10, TimeUnit.SECONDS) && runner.exitValue() == 0;
        }
        catch (IOException ex) {
            return false;
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void begin(List<String> command, URI readyCheck) throws IOException {
        if (this.containerImage != null) {
            beginContainer(command, readyCheck);
            return;
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(this.layoutDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile("application.log").toFile());
        this.process = builder.start();
        awaitReady(readyCheck);
    }

    /**
     * Start the recording container detached and remember its id, so it can later be
     * stopped gracefully with {@code <runtime> stop}. An attached {@code docker run} is not
     * usable here: killing its client leaves no way to send SIGTERM to the JVM inside, and
     * an abruptly killed JVM never assembles the cache.
     */
    private void beginContainer(List<String> command, URI readyCheck) throws IOException {
        // Capture stdout directly: with -d, the runtime prints the container id there.
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(this.layoutDirectory.toFile());
        builder.redirectErrorStream(true);
        Process runner = builder.start();
        String output = new String(runner.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exit = waitFor(runner, STOP_TIMEOUT);
        if (exit != 0) {
            Files.writeString(logFile("application.log"), output);
            throw new IOException("Unable to start the recording container: " + output);
        }
        this.containerName = output.lines().findFirst().orElse("").trim();
        if (this.containerName.isEmpty()) {
            throw new IOException("Unable to start the recording container: no container id was returned");
        }
        awaitReady(readyCheck);
    }

    private static int waitFor(Process process, Duration timeout) throws IOException {
        try {
            if (process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                return process.exitValue();
            }
            process.destroyForcibly();
            throw new IOException("Timed out after " + timeout);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for a process", ex);
        }
    }

    private Path logFile(String name) throws IOException {
        return Files.createDirectories(logDirectory()).resolve(name);
    }

    private Path logDirectory() {
        Path parent = this.layoutDirectory.toAbsolutePath().getParent();
        return ((parent != null) ? parent : this.layoutDirectory.toAbsolutePath()).resolve("logs");
    }

    private List<String> buildCommand(URI readyCheck) {
        if (this.containerImage != null) {
            return containerCommand(readyCheck);
        }
        return localCommand();
    }

    private List<String> localCommand() {
        List<String> command = new ArrayList<>();
        command.add(this.javaExecutable);
        command.add(AotCache.recordingArgument(this.cacheFile.toAbsolutePath()));
        command.add("-cp");
        command.add("runner.jar");
        command.add(this.startClass);
        command.addAll(this.applicationArguments);
        return command;
    }

    private List<String> containerCommand(URI readyCheck) {
        String cacheName = this.cacheFile.toAbsolutePath().getFileName().toString();
        int port = (readyCheck.getPort() >= 0) ? readyCheck.getPort() : defaultPort(readyCheck.getScheme());
        List<String> command = new ArrayList<>();
        command.add(this.containerRuntime);
        command.add("run");
        // Detached (-d) with a fixed name: the container is stopped through the runtime, which
        // delivers SIGTERM to the application so it exits cleanly and assembles the cache.
        // An attached run cannot be stopped gracefully from here.
        command.add("-d");
        command.add("--name");
        command.add(containerNameFor(readyCheck));
        // Publish the application port so the host-side tests can reach it, which works both
        // with native Linux host networking and with Docker Desktop's port forwarding.
        command.add("-p");
        command.add(port + ":" + port);
        command.add("-v");
        command.add(this.layoutDirectory.toAbsolutePath() + ":" + CONTAINER_APP_DIR);
        command.add("-v");
        command.add(this.cacheFile.toAbsolutePath().getParent() + ":" + CONTAINER_CACHE_DIR);
        command.add("-w");
        command.add(CONTAINER_APP_DIR);
        command.add("--entrypoint");
        command.add("java");
        command.add(this.containerImage);
        command.add(AotCache.recordingArgument(Path.of(CONTAINER_CACHE_DIR, cacheName)));
        command.add("-cp");
        command.add("runner.jar");
        command.add(this.startClass);
        command.addAll(this.applicationArguments);
        return command;
    }

    private String containerNameFor(URI readyCheck) {
        return "aot-cache-training-" + Integer.toHexString((this.cacheFile.toAbsolutePath() + readyCheck.toString()).hashCode());
    }

    private int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private void extract() throws IOException {
        clear(this.layoutDirectory);
        Path mainJar = extractArchive();
        Files.move(mainJar, this.layoutDirectory.resolve("runner.jar"));
        normalizeTimestamps(this.layoutDirectory);
    }

    private void clear(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            Files.createDirectories(directory);
            return;
        }
        try (var stream = Files.list(directory)) {
            for (Path child : stream.toList()) {
                deleteRecursively(child);
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException ex) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }

        });
    }

    private Path extractArchive() throws IOException {
        List<String> command = List.of(this.javaExecutable, "-Djarmode=tools", "-jar",
                this.appJar.toAbsolutePath().toString(), "extract", "--destination",
                this.layoutDirectory.toAbsolutePath().toString());
        Path log = logFile("extract.log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        Process extractor = builder.start();
        try {
            if (!extractor.waitFor(EXTRACT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                extractor.destroyForcibly();
                throw new IOException("Timed out extracting " + this.appJar);
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted extracting " + this.appJar, ex);
        }
        if (extractor.exitValue() != 0) {
            throw new IOException("Unable to extract " + this.appJar + " (exit " + extractor.exitValue() + "), see "
                    + log);
        }
        try (var stream = Files.list(this.layoutDirectory)) {
            return stream
                .filter((path) -> path.getFileName().toString().endsWith(".jar")
                        && !path.getFileName().toString().equals("runner.jar"))
                .findFirst()
                .orElseThrow(() -> new IOException("No application JAR found after extracting " + this.appJar));
        }
    }

    private void awaitReady(URI readyCheck) throws IOException {
        long deadline = System.nanoTime() + this.startTimeout.toNanos();
        IOException lastFailure = null;
        while (System.nanoTime() < deadline) {
            if (this.process != null && !this.process.isAlive()) {
                throw new IOException("Application exited with " + this.process.exitValue()
                        + " before becoming ready, see " + logDirectory().resolve("application.log"));
            }
            try {
                if (isReady(readyCheck)) {
                    return;
                }
            }
            catch (IOException ex) {
                lastFailure = ex;
            }
            sleep();
        }
        throw new IOException("Application did not become ready within " + this.startTimeout + " at " + readyCheck,
                lastFailure);
    }

    private boolean isReady(URI readyCheck) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) readyCheck.toURL().openConnection();
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(1000);
        connection.setInstanceFollowRedirects(false);
        try {
            int status = connection.getResponseCode();
            return status >= 200 && status < 400;
        }
        finally {
            connection.disconnect();
        }
    }

    private void sleep() throws IOException {
        try {
            Thread.sleep(250);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the application to become ready", ex);
        }
    }

    private void normalizeTimestamps(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.setLastModifiedTime(file, NORMALIZED_TIMESTAMP);
                return FileVisitResult.CONTINUE;
            }

        });
    }

}