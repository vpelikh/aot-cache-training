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

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.vpelikh.aot.AotCache;
import io.github.vpelikh.aot.JUnitPlatformVersion;
import io.github.vpelikh.aot.trainer.TrainingClasspath;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;

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
 * <p>When enabled, the plugin:
 * <ol>
 * <li>packages the main and test classes into JARs (the JVM refuses to record a cache
 * when the class path contains a non-empty directory);</li>
 * <li>runs the tests through {@code io.github.vpelikh.aot.trainer.TrainingLauncher} on a
 * JAR-only class path with {@code -XX:AOTCacheOutput=<buildDir>/aot-cache/application.aot};</li>
 * <li>verifies through the {@code verifyAotCache} task that a non-empty cache was produced.</li>
 * </ol>
 *
 * <p>This is deliberately separate from the normal {@code test} task: recording requires a
 * JAR-only class path that would slow down ordinary test runs, and the recorded class path
 * must match the one used at runtime.
 *
 * @author Vasily Pelikh
 */
public class AotCacheTrainingPlugin implements Plugin<Project> {

	/**
	 * The name of the task that records the cache.
	 */
	public static final String RECORD_TASK_NAME = "aotCacheTraining";

	/**
	 * The name of the verification task.
	 */
	public static final String VERIFY_TASK_NAME = "verifyAotCache";

	private static final String GROUP = "aot";

	@Override
	public void apply(Project project) {
		AotCacheTrainingExtension extension = project.getExtensions()
			.create("aotCacheTraining", AotCacheTrainingExtension.class);
		extension.getEnabled().convention(false);
		extension.getFailOnTestFailure().convention(true);
		extension.getAllowEmptyWorkload().convention(false);

		Path buildDirectory = project.getLayout().getBuildDirectory().get().getAsFile().toPath();
		Path cacheFile = AotCache.defaultCacheFile(buildDirectory);

		// The verify task is always registered so it exists regardless of plugin order.
		project.getTasks().register(VERIFY_TASK_NAME, (task) -> {
			task.setGroup(GROUP);
			task.setDescription("Verifies that the integration tests recorded a non-empty JVM AOT cache");
			task.doLast((unused) -> {
				if (!Boolean.TRUE.equals(extension.getEnabled().getOrElse(false))) {
					return;
				}
				long size = AotCache.verifyRecordedCache(cacheFile);
				if (size <= 0) {
					throw new IllegalStateException("AOT cache recording was enabled (aotCacheTraining.enabled = "
							+ "true) but no non-empty cache was found at " + cacheFile + ". Run on JDK "
							+ AotCache.MINIMUM_RECORDING_JDK
							+ "+ and make sure the training JVM exits cleanly (no System.exit mid-run).");
				}
			});
		});

		// Source sets and the jar task require the java plugin; react when it is applied so the
		// plugin can be listed before or after 'java' in the plugins block.
		project.getPlugins().withId("java", (java) -> configureJavaProject(project, extension, cacheFile));
	}

	private void configureJavaProject(Project project, AotCacheTrainingExtension extension, Path cacheFile) {
		// Package the test classes into a JAR so the training class path has no non-empty
		// directory, which the JVM requires for AOT cache recording.
		TaskProvider<Jar> mainJar = project.getTasks().named("jar", Jar.class);
		TaskProvider<Jar> testJar = project.getTasks().register("testJar", Jar.class, (jar) -> {
			SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
			jar.getArchiveClassifier().set("tests");
			jar.from(sourceSets.getByName(SourceSet.TEST_SOURCE_SET_NAME).getOutput());
		});

		TaskProvider<JavaExec> record = project.getTasks()
			.register(RECORD_TASK_NAME, JavaExec.class,
					(task) -> configureTraining(project, extension, task, cacheFile, mainJar, testJar));
		project.getTasks().named(RECORD_TASK_NAME, JavaExec.class).configure((task) -> task
			.onlyIf("AOT cache recording is enabled", (unused) -> Boolean.TRUE.equals(extension.getEnabled().getOrElse(false))));

		// Verification requires the recording task.
		project.getTasks().named(VERIFY_TASK_NAME).configure((task) -> task.dependsOn(record));
	}

	private void configureTraining(Project project, AotCacheTrainingExtension extension, JavaExec task, Path cacheFile,
			TaskProvider<Jar> mainJar, TaskProvider<Jar> testJar) {
		task.setGroup(GROUP);
		task.setDescription("Records a JVM AOT cache from the integration tests");

		task.dependsOn(mainJar, testJar);
		task.getMainClass().set("io.github.vpelikh.aot.trainer.TrainingLauncher");
		org.gradle.api.file.ConfigurableFileCollection classpath = project.getObjects()
			.fileCollection();
		classpath.from(mainJar, testJar);
		SourceSet test = project.getExtensions()
			.getByType(SourceSetContainer.class)
			.getByName(SourceSet.TEST_SOURCE_SET_NAME);
		// Only JAR entries are added: the JVM refuses to record a cache when the class path
		// contains a non-empty directory (build/classes, build/test-classes etc.).
		classpath.from(project.getProviders().provider(() -> test.getRuntimeClasspath()
			.getFiles()
			.stream()
			.filter(File::isFile)
			.filter((file) -> !io.github.vpelikh.aot.trainer.TrainingClasspath
				.isMockingLibrary(file.getName()))
			.toList()));
		// The launcher and its core helpers live on the plugin's class path, because the
		// plugin depends on the trainer module. They carry no JUnit. The JUnit Platform
		// generation comes from the project; the launcher itself (which junit-jupiter does
		// not bring) is resolved at the project's own platform version so the launcher API
		// and the test engine are never mixed across generations.
		classpath.from(project.getProviders().provider(() -> {
			List<File> launcher = new ArrayList<>();
			addCodeSource(launcher, AotCache.class);
			boolean projectHasLauncher = test.getRuntimeClasspath()
				.getFiles()
				.stream()
				.anyMatch((file) -> file.getName().startsWith("junit-platform-launcher-"));
			if (!projectHasLauncher) {
				launcher.addAll(resolveLauncher(project, test));
			}
			return launcher.stream().distinct().toList();
		}));
		classpath.from(project.getProviders().provider(
				() -> List.of(launcherJar(io.github.vpelikh.aot.trainer.TrainingLauncher.class))));
		task.setClasspath(classpath);
		task.getArgumentProviders().add(new AotCacheArgsProvider(extension));
		task.getJvmArgumentProviders()
			.add(new AotCacheArgumentProvider(extension.getEnabled(), project.getProviders().provider(() -> cacheFile)));
		task.getOutputs().file(cacheFile.toFile());
	}

	/**
	 * Add the JAR or classes directory that declares the given class.
	 * @param target the list to add to
	 * @param type a class from the component to add
	 */
	private static void addCodeSource(List<File> target, Class<?> type) {
		target.add(launcherJar(type));
	}

	private static File launcherJar(Class<?> type) {
		try {
			java.security.CodeSource codeSource = type.getProtectionDomain().getCodeSource();
			if (codeSource == null) {
				throw new IllegalStateException("No code source for " + type.getName());
			}
			return new File(codeSource.getLocation().toURI());
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to locate the code source for " + type.getName(), ex);
		}
	}

	/**
	 * Resolve the JUnit Platform launcher at the project's own JUnit Platform version, so the
	 * launcher API and the test engine belong to the same generation. Falls back to the
	 * launcher version this plugin was built against when the project brings none.
	 * @param project the project
	 * @param test the test source set
	 * @return the launcher JAR(s)
	 */
	private static List<File> resolveLauncher(Project project, SourceSet test) {
		List<Path> projectFiles = test.getRuntimeClasspath().getFiles().stream().map(File::toPath).toList();
		String version = JUnitPlatformVersion.find(projectFiles).orElse(DEFAULT_JUNIT_PLATFORM_VERSION);
		org.gradle.api.artifacts.Configuration configuration = project.getConfigurations()
			.detachedConfiguration(project.getDependencies()
				.create("org.junit.platform:junit-platform-launcher:" + version));
		configuration.setTransitive(true);
		return new ArrayList<>(configuration.resolve());
	}

	/**
	 * The JUnit Platform version this plugin was compiled against, used only when the project
	 * provides no JUnit Platform of its own.
	 */
	private static final String DEFAULT_JUNIT_PLATFORM_VERSION = "6.1.3";

}