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

import java.nio.file.Path;

import io.github.vpelikh.aot.AotCache;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.testing.Test;

/**
 * Gradle plugin that records a JVM AOT cache (JEP 483 / JEP 514) from the project's
 * integration tests.
 *
 * <p>Apply the plugin and opt in:
 *
 * <pre>{@code
 * plugins {
 *     id 'io.github.vpelikh.aot-cache-training'
 * }
 *
 * aotCacheTraining {
 *     enabled = true
 * }
 * }</pre>
 *
 * <p>When enabled, every {@link Test} task inherits
 * {@code -XX:AOTCacheOutput=<buildDir>/aot-cache/application.aot}, so the test JVM
 * records a cache that is assembled on clean exit. The conventional location matches what
 * the Paketo Spring Boot buildpack and container tooling look for
 * ({@code aot-cache/application.aot}).
 *
 * <p>The plugin also registers a {@code verifyAotCache} task that fails the build when
 * recording was requested but no non-empty cache was produced.
 *
 * @author Vasily Pelikh
 */
public class AotCacheTrainingPlugin implements Plugin<Project> {

	/**
	 * The name of the verification task.
	 */
	public static final String VERIFY_TASK_NAME = "verifyAotCache";

	private static final String VERIFY_TASK_DESCRIPTION = "Verifies that the integration tests recorded a non-empty JVM AOT cache";

	@Override
	public void apply(Project project) {
		AotCacheTrainingExtension extension = project.getExtensions()
			.create("aotCacheTraining", AotCacheTrainingExtension.class);
		extension.getEnabled().convention(false);

		Provider<Path> cacheFile = project.getProviders()
			.provider(() -> AotCache
				.defaultCacheFile(project.getLayout().getBuildDirectory().get().getAsFile().toPath()));

		project.getTasks().withType(Test.class).configureEach((test) -> {
			// Contribute the flag lazily so the decision is made when the test task runs.
			// This keeps the plugin configuration-cache compatible and works regardless of
			// when a build script sets aotCacheTraining.enabled.
			test.getJvmArgumentProviders().add(new AotCacheArgumentProvider(extension.getEnabled(), cacheFile));
		});

		TaskProvider<?> verify = project.getTasks().register(VERIFY_TASK_NAME, (task) -> {
			task.setGroup("verification");
			task.setDescription(VERIFY_TASK_DESCRIPTION);
			task.dependsOn(project.getTasks().withType(Test.class));
		});

		project.getTasks().named(VERIFY_TASK_NAME).configure((task) -> {
			Boolean enabled = extension.getEnabled().getOrElse(false);
			if (Boolean.TRUE.equals(enabled)) {
				task.doLast((unused) -> {
					Path file = cacheFile.get();
					long size = AotCache.verifyRecordedCache(file);
					if (size <= 0) {
						throw new IllegalStateException("AOT cache recording was enabled (aotCacheTraining.enabled = "
								+ "true) but no non-empty cache was found at " + file + ". Run the tests on JDK "
								+ AotCache.MINIMUM_RECORDING_JDK
								+ "+ and ensure the test JVM exits cleanly (no System.exit mid-run).");
					}
				});
			}
		});
	}

}