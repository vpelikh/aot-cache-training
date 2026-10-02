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

import java.nio.file.Path;

import io.github.vpelikh.aot.AotCache;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Configures the test JVM to record a JVM AOT cache (JEP 483 / JEP 514).
 *
 * <p>When enabled, this goal appends
 * {@code -XX:AOTCacheOutput=<build>/aot-cache/application.aot} to the project
 * {@code argLine} property, so that {@code maven-surefire-plugin} forks its test JVM with
 * recording enabled. The JVM assembles the cache on clean exit.
 *
 * <p>Enable via the {@code aot.cache.record} property or the {@code enabled} parameter:
 *
 * <pre>{@code
 * <plugin>
 *     <groupId>io.github.vpelikh</groupId>
 *     <artifactId>aot-cache-training-maven-plugin</artifactId>
 *     <executions>
 *         <execution>
 *             <goals>
 *                 <goal>record</goal>
 *             </goals>
 *         </execution>
 *     </executions>
 * </plugin>
 * }</pre>
 *
 * <p>By default the goal is bound to the {@code initialize} phase, before tests run.
 *
 * @author Vasily Pelikh
 */
@Mojo(name = "record", defaultPhase = LifecyclePhase.INITIALIZE, threadSafe = true)
public class AotCacheRecordMojo extends AbstractMojo {

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject project;

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
		String argument = AotCache.recordingArgument(cacheFile);
		appendToArgLine(argument);
		getLog().info("Configured AOT cache recording; the test JVM will assemble the cache at " + cacheFile);
	}

	Path resolveCacheFile() {
		return this.project.getBasedir().toPath()
			.resolve(this.project.getBuild().getDirectory())
			.resolve(this.cacheDirectory)
			.resolve(AotCache.CACHE_FILE_NAME)
			.toAbsolutePath()
			.normalize();
	}

	private void appendToArgLine(String additionalArgument) {
		String existing = this.project.getProperties().getProperty("argLine", "").trim();
		String combined = existing.isEmpty() ? additionalArgument : existing + " " + additionalArgument;
		this.project.getProperties().put("argLine", combined);
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

}