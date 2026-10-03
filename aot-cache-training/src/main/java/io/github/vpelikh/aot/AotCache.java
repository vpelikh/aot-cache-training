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

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.jspecify.annotations.Nullable;

/**
 * Central knowledge about the JVM AOT cache (JEP 483 / JEP 514) as recorded during a
 * training run.
 *
 * <p>This class is free of any test-framework dependency so it can be used by build
 * tooling and by the training launcher alike.
 *
 * <p>The single-step recording workflow introduced in JDK 25 (JEP 514) is a JVM
 * command-line flag:
 *
 * <pre>{@code
 * java -XX:AOTCacheOutput=build/aot-cache/application.aot -jar app.jar
 * }</pre>
 *
 * <p>The JVM records a temporary AOT configuration during the run and, on clean exit,
 * assembles the final cache at the given path. The flag can only be provided at JVM
 * startup, which is why recording is driven by test/build tooling rather than by a
 * library at runtime.
 *
 * @author Vasily Pelikh
 * @see <a href="https://openjdk.org/jeps/483">JEP 483: Ahead-of-Time Class Loading &amp; Linking</a>
 * @see <a href="https://openjdk.org/jeps/514">JEP 514: Ahead-of-Time Command-Line Ergonomics</a>
 */
public final class AotCache {

    /**
     * The JVM flag that enables single-step AOT cache recording.
     */
    public static final String OUTPUT_FLAG = "-XX:AOTCacheOutput=";

    /**
     * The minimum JDK feature version that supports single-step recording (JDK 25, JEP 514).
     */
    public static final int MINIMUM_RECORDING_JDK = 25;

    /**
     * The conventional file name of a recorded cache within an {@code aot-cache} directory.
     */
    public static final String CACHE_FILE_NAME = "application.aot";

    /**
     * The conventional directory that holds a recorded cache, relative to the build
     * output or application content.
     */
    public static final String CACHE_DIRECTORY = "aot-cache";

    private AotCache() {
    }

    /**
     * Return {@code true} if the given JVM input arguments enable single-step AOT cache
     * recording.
     * @param jvmArguments the JVM command-line arguments
     * @return {@code true} if {@value #OUTPUT_FLAG} is present
     */
    public static boolean isRecordingEnabled(List<String> jvmArguments) {
        return findOutputPath(jvmArguments) != null;
    }

    /**
     * Find the AOT cache output path configured on the given JVM input arguments.
     * @param jvmArguments the JVM command-line arguments
     * @return the configured output path, or {@code null} if recording is not enabled
     */
    public static @Nullable String findOutputPath(List<String> jvmArguments) {
        for (String argument : jvmArguments) {
            if (argument.startsWith(OUTPUT_FLAG)) {
                String value = argument.substring(OUTPUT_FLAG.length()).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Return the JVM input arguments of the current process, excluding arguments passed
     * to the main method.
     * @return the current JVM input arguments
     */
    public static List<String> currentJvmArguments() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments();
    }

    /**
     * Resolve the conventional cache file path within the given build output directory.
     * @param buildOutputDirectory the build output directory (for example {@code build/}
     * or {@code target/})
     * @return the path to {@code <buildOutputDirectory>/aot-cache/application.aot}
     */
    public static Path defaultCacheFile(Path buildOutputDirectory) {
        return buildOutputDirectory.resolve(CACHE_DIRECTORY).resolve(CACHE_FILE_NAME);
    }

    /**
     * Copy an application JAR and append the recorded cache as {@code aot-cache/application.aot},
     * so a buildpack finds it inside the packaged application content.
     *
     * <p>Existing entries are copied verbatim, including their compression method, so stored
     * nested JARs stay stored. The appended entries are given a POSIX mode ({@code 0644} for
     * the file, {@code 0755} for the directory) by patching the ZIP central directory:
     * {@link ZipEntry} cannot store one, and an entry without a mode extracts
     * without read permission, which makes the buildpack fail with "permission denied".
     * @param bootJar the application JAR to copy
     * @param cacheFile the recorded cache to embed
     * @param outputJar the destination JAR (overwritten)
     * @throws IOException if the JARs cannot be read or written
     */
    public static void embedCacheIntoJar(Path bootJar, Path cacheFile, Path outputJar) throws IOException {
        String directoryEntry = CACHE_DIRECTORY + "/";
        String fileEntry = directoryEntry + CACHE_FILE_NAME;
        Path parent = outputJar.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.deleteIfExists(outputJar);
        try (ZipFile zip = new ZipFile(bootJar.toFile());
                ZipOutputStream out = new ZipOutputStream(
                        Files.newOutputStream(outputJar))) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                // Skip any cache entry already present, so embedding into an already-embedded
                // JAR replaces it instead of adding a duplicate.
                if (entry.getName().equals(directoryEntry) || entry.getName().equals(fileEntry)) {
                    continue;
                }
                out.putNextEntry(new ZipEntry(entry));
                if (!entry.isDirectory()) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        in.transferTo(out);
                    }
                }
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry(directoryEntry));
            out.closeEntry();
            out.putNextEntry(new ZipEntry(fileEntry));
            try (InputStream in = Files.newInputStream(cacheFile)) {
                in.transferTo(out);
            }
            out.closeEntry();
        }
        applyUnixModes(outputJar, directoryEntry, fileEntry);
    }

    private static final int UNIX_FILE_MODE = 0100644;

    private static final int UNIX_DIR_MODE = 0040755;

    /** "version made by": Unix creator OS (3) and ZIP spec 3.0 (30). */
    private static final int VERSION_MADE_BY_UNIX = (3 << 8) | 30;

    private static final long CENTRAL_HEADER_SIGNATURE = 0x02014b50L;

    private static final long END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50L;

    private static final int CENTRAL_HEADER_SIZE = 46;

    private static void applyUnixModes(Path jar, String directoryEntry, String fileEntry) throws IOException {
        byte[] bytes = Files.readAllBytes(jar);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int offset = findCentralDirectoryOffset(buffer);
        while (offset + CENTRAL_HEADER_SIZE <= bytes.length
                && Integer.toUnsignedLong(buffer.getInt(offset)) == CENTRAL_HEADER_SIGNATURE) {
            int nameLength = Short.toUnsignedInt(buffer.getShort(offset + 28));
            int extraLength = Short.toUnsignedInt(buffer.getShort(offset + 30));
            int commentLength = Short.toUnsignedInt(buffer.getShort(offset + 32));
            String name = new String(bytes, offset + CENTRAL_HEADER_SIZE, nameLength,
                    StandardCharsets.UTF_8);
            int mode = 0;
            if (name.equals(directoryEntry)) {
                mode = UNIX_DIR_MODE;
            }
            else if (name.equals(fileEntry)) {
                mode = UNIX_FILE_MODE;
            }
            if (mode != 0) {
                // "version made by" must say Unix, otherwise readers ignore the mode; the
                // external attributes hold the mode in their high 16 bits.
                buffer.putShort(offset + 4, (short) VERSION_MADE_BY_UNIX);
                buffer.putInt(offset + 38, mode << 16);
            }
            offset += CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength;
        }
        Files.write(jar, bytes);
    }

    private static int findCentralDirectoryOffset(ByteBuffer buffer) throws IOException {
        int length = buffer.capacity();
        ByteBuffer tail = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        // The end-of-central-directory record is at most 22 bytes plus a 65535-byte comment.
        for (int candidate = length - 22; candidate >= Math.max(0, length - 22 - 65535); candidate--) {
            tail.position(candidate);
            if (Integer.toUnsignedLong(tail.getInt()) == END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                return tail.getInt(candidate + 16);
            }
        }
        throw new IOException("Not a valid ZIP/JAR: end-of-central-directory record not found");
    }

    /**
     * Build the JVM argument that enables recording to the given path.
     * @param outputPath the cache output path
     * @return the {@value #OUTPUT_FLAG} argument
     */
    public static String recordingArgument(Path outputPath) {
        return OUTPUT_FLAG + outputPath.toAbsolutePath();
    }

    /**
     * Verify that a cache was recorded at the given path.
     *
     * <p>This must be called by build tooling <em>after</em> the test JVM has exited: the
     * JVM assembles the final cache only after shutdown hooks have run, so a check from
     * within the test JVM (for example, a shutdown hook) would always report a missing
     * cache.
     * @param cacheFile the expected cache file
     * @return the size of the recorded cache in bytes, or {@code -1} if it was not recorded
     */
    public static long verifyRecordedCache(Path cacheFile) {
        if (Files.isRegularFile(cacheFile)) {
            try {
                return Files.size(cacheFile);
            }
            catch (IOException ex) {
                return -1;
            }
        }
        return -1;
    }

}
