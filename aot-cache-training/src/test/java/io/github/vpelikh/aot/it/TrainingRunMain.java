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

import java.io.PrintWriter;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * Entry point executed in a forked JVM by the end-to-end recording test. It runs
 * {@link TrainingApplicationTests} through the JUnit Platform so that the Spring
 * TestContext framework (and therefore our listener) participates in the run.
 *
 * @author Vasily Pelikh
 */
public final class TrainingRunMain {

	private TrainingRunMain() {
	}

	public static void main(String[] args) {
		LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
			.selectors(DiscoverySelectors.selectClass(TrainingApplicationTests.class))
			.build();
		Launcher launcher = LauncherFactory.create();
		SummaryGeneratingListener listener = new SummaryGeneratingListener();
		launcher.execute(request, listener);
		TestExecutionSummary summary = listener.getSummary();
		PrintWriter writer = new PrintWriter(System.out, true);
		summary.printTo(writer);
		if (summary.getTotalFailureCount() > 0) {
			summary.printFailuresTo(writer);
			System.exit(1);
		}
	}

}