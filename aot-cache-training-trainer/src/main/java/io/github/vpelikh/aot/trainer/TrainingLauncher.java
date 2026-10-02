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

import java.util.List;

import io.github.vpelikh.aot.AotCache;

/**
 * Runs a set of tests as the training workload for a JVM AOT cache, then exits cleanly so
 * the JVM can assemble the cache.
 *
 * <p>This entry point exists because of a hard JVM constraint (see
 * <a href="https://openjdk.org/jeps/483">JEP 483</a>): {@code -XX:AOTCacheOutput} refuses
 * to record a cache when <em>any</em> class path entry is a non-empty directory. Standard
 * test runners put compiled classes on the class path as directories
 * ({@code build/classes}, {@code target/test-classes}), which makes recording fail with
 * {@code Cannot have non-empty directory in paths}. The build plugins therefore package
 * the application and test classes into JARs and invoke this launcher on a JAR-only class
 * path.
 *
 * <p>Usage (all arguments optional):
 *
 * <pre>{@code
 * java -cp <jars> io.github.vpelikh.aot.trainer.TrainingLauncher \
 *      --scan-classpath \
 *      --select-package com.example \
 *      --fail-on-test-failure
 * }</pre>
 *
 * <ul>
 * <li>{@code --scan-classpath} discovers tests on the class path (default).</li>
 * <li>{@code --select-package <pkg>} / {@code --select-class <fqcn>} restrict discovery.</li>
 * <li>{@code --fail-on-test-failure} exits non-zero if any test fails (default: {@code true}).</li>
 * <li>{@code --allow-empty} exits zero when no tests are discovered (default: {@code false}).</li>
 * </ul>
 *
 * @author Vasily Pelikh
 */
public final class TrainingLauncher {

    private TrainingLauncher() {
    }

    /**
     * Run the training workload.
     * @param args command-line arguments, see the class Javadoc
     */
    public static void main(String[] args) {
        List<String> arguments = List.of(args);
        String outputPath = AotCache.findOutputPath(AotCache.currentJvmArguments());
        if (outputPath != null) {
            System.out.println("[aot-cache-training] Recording an AOT cache to " + outputPath
                    + " (the JVM assembles it on clean exit).");
        }
        else {
            System.out.println("[aot-cache-training] Warning: no -XX:AOTCacheOutput flag detected on the JVM "
                    + "command line; the tests will run but no cache will be recorded.");
        }
        System.exit(TestRun.execute(arguments));
    }

}
