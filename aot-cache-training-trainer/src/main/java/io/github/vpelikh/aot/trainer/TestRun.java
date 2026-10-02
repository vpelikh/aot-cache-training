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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * Runs a JUnit Platform test selection and reports the outcome, translating the results to
 * an exit code. Shared by {@link TrainingLauncher} and {@link OutOfProcessTrainingLauncher}
 * so both training modes behave identically with respect to failing and empty workloads.
 *
 * @author Vasily Pelikh
 */
final class TestRun {

    private static final String SUMMARY_PREFIX = "[aot-cache-training] ";

    private TestRun() {
    }

    /**
     * Run the tests selected by the given arguments and return the process exit code.
     * @param arguments the command-line arguments
     * @return {@code 0} on success, {@code 1} on test failure, {@code 2} on a missing engine
     * or an empty workload
     */
    static int execute(List<String> arguments) {
        LauncherDiscoveryRequest request = buildRequest(arguments);
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        try {
            Launcher launcher = LauncherFactory.create();
            launcher.execute(request, listener);
        }
        catch (org.junit.platform.commons.PreconditionViolationException ex) {
            System.err.println(SUMMARY_PREFIX + "No JUnit test engine was found on the training class path, so "
                    + "no tests can run and no cache can be recorded. Add an engine such as "
                    + "org.junit.jupiter:junit-jupiter (or junit-jupiter-engine) as a test dependency. Details: "
                    + ex.getMessage());
            return 2;
        }

        TestExecutionSummary summary = listener.getSummary();
        PrintWriter writer = new PrintWriter(System.out, true);
        summary.printTo(writer);
        long failures = summary.getTotalFailureCount();
        boolean failOnTestFailure = !arguments.contains("--no-fail-on-test-failure");
        if (failures > 0) {
            summary.printFailuresTo(writer);
            if (failOnTestFailure) {
                writer.flush();
                return 1;
            }
        }
        if (summary.getTestsFoundCount() == 0) {
            writer.println(SUMMARY_PREFIX + "No tests were discovered on the class path, so the recorded cache "
                    + "would be empty and useless. Check your test sources and any configured packages to scan. "
                    + "Pass --allow-empty to proceed anyway.");
            writer.flush();
            if (!arguments.contains("--allow-empty")) {
                return 2;
            }
        }
        return 0;
    }

    private static LauncherDiscoveryRequest buildRequest(List<String> arguments) {
        List<DiscoverySelector> selectors = new ArrayList<>();
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
            builder.selectors(DiscoverySelectors.selectClasspathRoots(classpathRoots()));
        }
        else {
            builder.selectors(selectors);
        }
        return builder.build();
    }

    private static Set<Path> classpathRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(java.io.File.pathSeparator)) {
            if (!entry.isBlank()) {
                roots.add(Paths.get(entry));
            }
        }
        return roots;
    }

}