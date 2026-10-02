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

import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import io.github.vpelikh.aot.AotCache;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

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

		LauncherDiscoveryRequest request = buildRequest(arguments);
		Launcher launcher = LauncherFactory.create();
		SummaryGeneratingListener listener = new SummaryGeneratingListener();
		launcher.execute(request, listener);

		TestExecutionSummary summary = listener.getSummary();
		PrintWriter writer = new PrintWriter(System.out, true);
		summary.printTo(writer);
		long failures = summary.getTotalFailureCount();
		boolean failOnTestFailure = !arguments.contains("--no-fail-on-test-failure");
		if (failures > 0) {
			summary.printFailuresTo(writer);
			if (failOnTestFailure) {
				writer.flush();
				System.exit(1);
			}
		}
		if (summary.getTestsFoundCount() == 0) {
			writer.println("[aot-cache-training] No tests were discovered; the cache would be empty.");
			writer.flush();
			if (!arguments.contains("--allow-empty")) {
				System.exit(2);
			}
		}
	}

	private static LauncherDiscoveryRequest buildRequest(List<String> arguments) {
		List<org.junit.platform.engine.DiscoverySelector> selectors = new ArrayList<>();
		boolean explicitSelectors = false;
		for (int i = 0; i < arguments.size(); i++) {
			String argument = arguments.get(i);
			if ("--select-package".equals(argument) && i + 1 < arguments.size()) {
				selectors.add(DiscoverySelectors.selectPackage(arguments.get(++i)));
				explicitSelectors = true;
			}
			else if ("--select-class".equals(argument) && i + 1 < arguments.size()) {
				selectors.add(DiscoverySelectors.selectClass(arguments.get(++i)));
				explicitSelectors = true;
			}
			else if (argument.startsWith("--select-package=")) {
				selectors.add(DiscoverySelectors.selectPackage(argument.substring("--select-package=".length())));
				explicitSelectors = true;
			}
			else if (argument.startsWith("--select-class=")) {
				selectors.add(DiscoverySelectors.selectClass(argument.substring("--select-class=".length())));
				explicitSelectors = true;
			}
		}

		LauncherDiscoveryRequestBuilder builder = LauncherDiscoveryRequestBuilder.request();
		if (!explicitSelectors) {
			// Discover every test on the (JAR-only) class path.
			builder.selectors(DiscoverySelectors.selectClasspathRoots(classpathRoots()));
		}
		else {
			builder.selectors(selectors);
		}
		return builder.build();
	}

	private static java.util.Set<Path> classpathRoots() {
		java.util.Set<Path> roots = new java.util.LinkedHashSet<>();
		String classpath = System.getProperty("java.class.path", "");
		for (String entry : classpath.split(java.io.File.pathSeparator)) {
			if (!entry.isBlank()) {
				roots.add(Paths.get(entry));
			}
		}
		return roots;
	}

}