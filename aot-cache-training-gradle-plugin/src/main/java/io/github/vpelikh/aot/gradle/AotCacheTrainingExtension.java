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

    /**
     * Whether to record the cache for the packaged application by running it in its own JVM
     * and driving it from the integration tests over HTTP (out of process).
     *
     * <p>This is required to produce a cache the container image can load: an AOT cache only
     * loads against the exact class path it was recorded with, and the packaged application's
     * class path differs from a test class path. The tests must therefore be black-box tests
     * that call the application over HTTP (the base URL is exposed to them through the
     * {@code aot.training.url} system property). Defaults to {@code false}, which uses the
     * in-process mode (the tests run in the JVM that records the cache).
     * @return the out-of-process property
     */
    public abstract Property<Boolean> getOutOfProcess();

    /**
     * A URL polled until it returns a 2xx/3xx response, used as the readiness check and the
     * base URL for out-of-process training. Defaults to {@code http://localhost:8080/}.
     * @return the readiness URL property
     */
    public abstract Property<String> getReadyUrl();

    /**
     * The application start class used for out-of-process training. When unset, it is read
     * from the packaged JAR's {@code Start-Class} manifest attribute.
     * @return the start-class property
     */
    public abstract Property<String> getStartClass();

    /**
     * A container image whose JVM records the out-of-process cache. When set, the packaged
     * application runs inside that image, so the recorded cache matches the image's JVM build
     * and architecture and can be loaded by that image at runtime. When unset, the local JVM
     * records the cache (usable when it matches the runtime JVM).
     * @return the container-image property
     */
    public abstract Property<String> getContainerImage();

    /**
     * The container runtime executable used when {@link #getContainerImage()} is set.
     * Defaults to {@code docker}.
     * @return the container-runtime property
     */
    public abstract Property<String> getContainerRuntime();

    /**
     * Arguments passed to the application during out-of-process training (for example, to set
     * a Spring profile).
     * @return the application-arguments property
     */
    public abstract ListProperty<String> getApplicationArguments();

    /**
     * How long to wait for the application to become ready during out-of-process training, in
     * seconds. Defaults to {@code 120}.
     * @return the start-timeout property
     */
    public abstract Property<Integer> getStartTimeout();

}
