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

package io.github.vpelikh.aot.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.vpelikh.aot.AotCache;
import io.github.vpelikh.aot.JUnitPlatformVersion;
import io.github.vpelikh.aot.trainer.TrainingClasspath;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Records a JVM AOT cache (JEP 483 / JEP 514) from the project's tests.
 *
 * <p>This goal runs the tests through
 * {@code io.github.vpelikh.aot.trainer.TrainingLauncher} in a forked JVM started with
 * {@code -XX:AOTCacheOutput=<build>/aot-cache/application.aot}. The JVM assembles the cache
 * on clean exit.
 *
 * <p>The application and test classes are packaged into JARs first: the JVM refuses to
 * record a cache when the class path contains a non-empty directory
 * ({@code Cannot have non-empty directory in paths}).
 *
 * <p>Enable via the {@code aot.cache.record} property or the {@code enabled} parameter. By
 * default the goal is bound to the {@code process-test-classes} phase, after the test
 * classes have been compiled.
 *
 * @author Vasily Pelikh
 */
@Mojo(name = "record", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
		requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.TEST, threadSafe = true)
public class AotCacheRecordMojo extends AbstractMojo {

	private static final String DEFAULT_JUNIT_PLATFORM_VERSION = "1.14.4";

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject project;

	@org.apache.maven.plugins.annotations.Component
	private org.eclipse.aether.RepositorySystem repositorySystem;

	@Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
	private org.eclipse.aether.RepositorySystemSession repositorySystemSession;

	@Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
	private java.util.List<org.eclipse.aether.repository.RemoteRepository> remoteRepositories;

	/**
	 * Whether to record an AOT cache. Can also be set with {@code -Daot.cache.record=true}.
	 */
	@Parameter(property = "aot.cache.record", defaultValue = "false")
	private boolean enabled;

	/**
	 * Skip execution entirely. Can also be set with {@code -Daot.cache.skip=true}.
	 */
	@Parameter(property = "aot.cache.skip", defaultValue = "false")
	private boolean skip;

	/**
	 * Directory that holds the recorded cache, relative to the project build directory.
	 */
	@Parameter(defaultValue = "aot-cache")
	private String cacheDirectory = AotCache.CACHE_DIRECTORY;

	/**
	 * Packages whose tests form the training workload. When empty, the whole test class path
	 * is scanned.
	 */
	@Parameter
	private List<String> packagesToScan = new ArrayList<>();

	/**
	 * Whether a failing test should fail the build. Defaults to {@code true}.
	 */
	@Parameter(defaultValue = "true")
	private boolean failOnTestFailure = true;

	/**
	 * Whether to record a cache even when the training run discovers no tests. Defaults to
	 * {@code false}, because an empty workload records a large but useless cache.
	 */
	@Parameter(defaultValue = "false")
	private boolean allowEmptyWorkload = false;

	@Override
	public void execute() throws MojoExecutionException {
		if (this.skip) {
			getLog().debug("AOT cache recording is skipped (aot.cache.skip=true).");
			return;
		}
		if (!this.enabled) {
			getLog().debug("AOT cache recording is not enabled (aot.cache.record is not true).");
			return;
		}
		Path cacheFile = resolveCacheFile();
		Path workDirectory = cacheFile.getParent().resolve("classpath");
		try {
			Files.createDirectories(workDirectory);
			List<String> command = buildCommand(cacheFile, workDirectory);
			getLog().info("Recording AOT cache to " + cacheFile + " (JAR-only class path).");
			if (getLog().isDebugEnabled()) {
				getLog().debug("Training command: " + String.join(" ", command));
			}
			int exitCode = run(command);
			if (exitCode != 0) {
				throw new MojoExecutionException(
						"AOT cache training run failed with exit code " + exitCode + ". See the output above for details.");
			}
		}
		catch (IOException ex) {
			throw new MojoExecutionException("Unable to prepare the AOT cache training run", ex);
		}
	}

	private List<String> buildCommand(Path cacheFile, Path workDirectory) throws IOException {
		List<String> command = new ArrayList<>();
		command.add(javaExecutable());
		command.add(AotCache.recordingArgument(cacheFile));
		command.add("-cp");
		command.add(jarOnlyClasspath(workDirectory));
		command.add("io.github.vpelikh.aot.trainer.TrainingLauncher");
		for (String packageName : this.packagesToScan) {
			command.add("--select-package=" + packageName);
		}
		if (!this.failOnTestFailure) {
			command.add("--no-fail-on-test-failure");
		}
		if (this.allowEmptyWorkload) {
			command.add("--allow-empty");
		}
		return command;
	}

	/**
	 * Build a class path with no non-empty directory, as required by the JVM for AOT cache
	 * recording. Directory entries (for example {@code target/test-classes}) are packaged
	 * into JARs; JAR entries are kept as-is.
	 */
	String jarOnlyClasspath(Path workDirectory) throws IOException {
		List<Path> elements = new ArrayList<>();
		try {
			for (String element : this.project.getTestClasspathElements()) {
				elements.add(Path.of(element));
			}
		}
		catch (org.apache.maven.artifact.DependencyResolutionRequiredException ex) {
			throw new IOException("Unable to resolve the test class path", ex);
		}
		// The launcher and its core helpers come from the plugin's own class path; they carry
		// no JUnit. The JUnit Platform generation comes from the project, so a project on a
		// newer generation (for example JUnit 6) is never mixed with ours.
		elements.addAll(pluginClasspath());
		// junit-jupiter does not bring the launcher, so add it unless the project already
		// provides one, always at the project's own JUnit Platform version.
		if (!hasJar(elements, "junit-platform-launcher-")) {
			elements.add(resolveLauncher());
		}
		// Mocking libraries self-attach agents that break cache assembly.
		elements = new ArrayList<>(TrainingClasspath.withoutMockingLibraries(elements));
		return TrainingClasspath.jarOnlyClasspath(elements, workDirectory);
	}

	/**
	 * Collect the launcher and its core helpers from the plugin's own class path. These
	 * deliberately do not include JUnit; the JUnit Platform comes from the project.
	 * @return the plugin class path entries to append
	 */
	private List<Path> pluginClasspath() {
		List<Path> elements = new ArrayList<>();
		addCodeSource(elements, io.github.vpelikh.aot.trainer.TrainingLauncher.class);
		addCodeSource(elements, AotCache.class);
		return elements;
	}

	/**
	 * Resolve the JUnit Platform launcher at the project's own JUnit Platform version, so the
	 * launcher API and the test engine belong to the same generation. Falls back to the
	 * launcher version this plugin was built against when the project brings no JUnit Platform.
	 */
	private Path resolveLauncher() throws IOException {
		String version = JUnitPlatformVersion.find(projectClasspathElements()).orElse(DEFAULT_JUNIT_PLATFORM_VERSION);
		try {
			org.eclipse.aether.artifact.Artifact artifact = new org.eclipse.aether.artifact.DefaultArtifact(
					"org.junit.platform", "junit-platform-launcher", "jar", version);
			org.eclipse.aether.resolution.ArtifactRequest request = new org.eclipse.aether.resolution.ArtifactRequest(
					artifact, this.remoteRepositories, null);
			org.eclipse.aether.resolution.ArtifactResult result = this.repositorySystem
				.resolveArtifact(this.repositorySystemSession, request);
			return result.getArtifact().getFile().toPath();
		}
		catch (org.eclipse.aether.resolution.ArtifactResolutionException ex) {
			throw new IOException("Unable to resolve org.junit.platform:junit-platform-launcher:" + version
					+ ". The AOT cache training run needs the JUnit Platform launcher on the training class path.", ex);
		}
	}

	private List<Path> projectClasspathElements() {
		List<Path> elements = new ArrayList<>();
		try {
			for (String element : this.project.getTestClasspathElements()) {
				elements.add(Path.of(element));
			}
		}
		catch (org.apache.maven.artifact.DependencyResolutionRequiredException ex) {
			// Fall through: treat as an empty class path, so the default launcher version is used.
		}
		return elements;
	}

	private boolean hasJar(List<Path> elements, String namePrefix) {
		return elements.stream()
			.map((element) -> element.getFileName().toString())
			.anyMatch((name) -> name.startsWith(namePrefix));
	}

	private void addCodeSource(List<Path> target, Class<?> type) {
		try {
			java.security.CodeSource codeSource = type.getProtectionDomain().getCodeSource();
			if (codeSource != null) {
				target.add(Path.of(codeSource.getLocation().toURI()));
			}
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to locate the code source for " + type.getName(), ex);
		}
	}

	private String javaExecutable() {
		return Path.of(System.getProperty("java.home"), "bin", "java").toString();
	}

	private int run(List<String> command) throws IOException {
		Process process = new ProcessBuilder(command).inheritIO().start();
		try {
			return process.waitFor();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while waiting for the AOT cache training run", ex);
		}
	}

	Path resolveCacheFile() {
		return this.project.getBasedir()
			.toPath()
			.resolve(this.project.getBuild().getDirectory())
			.resolve(this.cacheDirectory)
			.resolve(AotCache.CACHE_FILE_NAME)
			.toAbsolutePath()
			.normalize();
	}

	void setProject(MavenProject project) {
		this.project = project;
	}

	void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	void setSkip(boolean skip) {
		this.skip = skip;
	}

	void setCacheDirectory(String cacheDirectory) {
		this.cacheDirectory = cacheDirectory;
	}

	void setPackagesToScan(List<String> packagesToScan) {
		this.packagesToScan = packagesToScan;
	}

	void setFailOnTestFailure(boolean failOnTestFailure) {
		this.failOnTestFailure = failOnTestFailure;
	}

	void setAllowEmptyWorkload(boolean allowEmptyWorkload) {
		this.allowEmptyWorkload = allowEmptyWorkload;
	}

}