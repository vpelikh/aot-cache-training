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

import java.util.List;

import io.github.vpelikh.aot.AotCache;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * Lazily contributes the {@code -XX:AOTCacheOutput} flag to a test JVM.
 *
 * <p>The decision is made when the task executes rather than when it is configured, so
 * the plugin stays compatible with the configuration cache and with build scripts that
 * set {@code aotCacheTraining.enabled} at any point during evaluation.
 *
 * @author Vasily Pelikh
 */
class AotCacheArgumentProvider implements CommandLineArgumentProvider {

	private final Property<Boolean> enabled;

	private final Provider<java.nio.file.Path> cacheFile;

	AotCacheArgumentProvider(Property<Boolean> enabled, Provider<java.nio.file.Path> cacheFile) {
		this.enabled = enabled;
		this.cacheFile = cacheFile;
	}

	@Override
	public Iterable<String> asArguments() {
		if (Boolean.TRUE.equals(this.enabled.getOrElse(false))) {
			return List.of(AotCache.recordingArgument(this.cacheFile.get()));
		}
		return List.of();
	}

}