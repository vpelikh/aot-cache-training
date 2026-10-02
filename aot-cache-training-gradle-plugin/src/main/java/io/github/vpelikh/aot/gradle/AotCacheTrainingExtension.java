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

import org.gradle.api.provider.Property;

/**
 * Configuration for the {@code io.github.vpelikh.aot-cache-training} plugin.
 *
 * <pre>{@code
 * aotCacheTraining {
 *     enabled = true
 * }
 * }</pre>
 */
public abstract class AotCacheTrainingExtension {

	/**
	 * Whether AOT cache recording is enabled. Defaults to {@code false} so the plugin is
	 * inert until a user opts in.
	 * @return the enabled property
	 */
	public abstract Property<Boolean> getEnabled();

}