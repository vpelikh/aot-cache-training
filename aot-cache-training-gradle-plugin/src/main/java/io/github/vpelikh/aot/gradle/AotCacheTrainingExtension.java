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

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * Configuration for the {@code io.github.vpelikh.aot-cache-training} plugin.
 *
 * <pre>{@code
 * aotCacheTraining {
 *     enabled = true
 *     packagesToScan = ['com.example']
 * }
 * }</pre>
 */
public abstract class AotCacheTrainingExtension {

    /**
     * Create the extension. Gradle instantiates it and injects the managed properties.
     */
    public AotCacheTrainingExtension() {
    }

    /**
     * Whether AOT cache recording is enabled. Defaults to {@code false} so the plugin is
     * inert until a user opts in.
     * @return the enabled property
     */
    public abstract Property<Boolean> getEnabled();

    /**
     * Packages whose tests form the training workload. When empty, the whole test class
     * path is scanned.
     * @return the packages to scan
     */
    public abstract ListProperty<String> getPackagesToScan();

    /**
     * Whether a failing test should fail the training run. Defaults to {@code true}; use
     * {@code false} to record a cache even when some integration tests fail.
     * @return the fail-on-test-failure property
     */
    public abstract Property<Boolean> getFailOnTestFailure();

    /**
     * Whether to record a cache even when the training run discovers no tests. Defaults to
     * {@code false}, because an empty workload records a large but useless cache and is
     * almost always a misconfiguration.
     * @return the allow-empty-workload property
     */
    public abstract Property<Boolean> getAllowEmptyWorkload();

}
