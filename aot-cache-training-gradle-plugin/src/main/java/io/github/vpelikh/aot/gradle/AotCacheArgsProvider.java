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

package io.github.vpelikh.aot.gradle;

import java.util.ArrayList;
import java.util.List;

import org.gradle.process.CommandLineArgumentProvider;

/**
 * Contributes the training launcher arguments lazily, so {@code packagesToScan} and
 * {@code failOnTestFailure} are read when the task runs.
 *
 * @author Vasily Pelikh
 */
class AotCacheArgsProvider implements CommandLineArgumentProvider {

	private final AotCacheTrainingExtension extension;

	AotCacheArgsProvider(AotCacheTrainingExtension extension) {
		this.extension = extension;
	}

	@Override
	public Iterable<String> asArguments() {
		List<String> args = new ArrayList<>();
		for (String packageName : this.extension.getPackagesToScan().getOrElse(List.of())) {
			args.add("--select-package=" + packageName);
		}
		if (!Boolean.TRUE.equals(this.extension.getFailOnTestFailure().getOrElse(true))) {
			args.add("--no-fail-on-test-failure");
		}
		// The verify task, not the training run, owns the fail-closed behaviour when a project
		// has no tests to contribute to the training workload.
		args.add("--allow-empty");
		return args;
	}

}