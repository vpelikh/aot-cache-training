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
 * Verifies that a non-empty JVM AOT cache was recorded by the test run.
 *
 * <p>This goal fails the build when recording was requested
 * (<code>aot.cache.record=true</code>) but no cache is present. Verification happens
 * <em>after</em> the test JVM has exited: the JVM assembles the cache only after shutdown
 * hooks run, so an in-JVM check would always report a missing cache.
 *
 * <p>By default the goal is bound to the <code>verify</code> phase.
 *
 * @author Vasily Pelikh
 */
@Mojo(name = "verify", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true)
public class AotCacheVerifyMojo extends AbstractMojo {

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	private MavenProject project;

	/**
	 * Whether AOT cache recording was enabled. Can also be set with
	 * <code>-Daot.cache.record=true</code>.
	 */
	@Parameter(property = "aot.cache.record", defaultValue = "false")
	private boolean enabled;

	/**
	 * Skip execution entirely. Can also be set with <code>-Daot.cache.skip=true</code>.
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
		if (this.skip || !this.enabled) {
			return;
		}
		Path cacheFile = resolveCacheFile();
		long size = AotCache.verifyRecordedCache(cacheFile);
		if (size <= 0) {
			throw new MojoExecutionException("AOT cache recording was enabled (aot.cache.record=true) but no "
					+ "non-empty cache was found at " + cacheFile + ". The training run needs JDK "
					+ AotCache.MINIMUM_RECORDING_JDK + "+ and a clean exit; see the 'record' goal output above.");
		}
		getLog().info("Verified AOT cache at " + cacheFile + " (" + size + " bytes).");
	}

	Path resolveCacheFile() {
		return this.project.getBasedir().toPath()
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

}