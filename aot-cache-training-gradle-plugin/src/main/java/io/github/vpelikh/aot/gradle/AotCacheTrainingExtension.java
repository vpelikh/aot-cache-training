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
     * A URL polled until it returns a 2xx/3xx response, used as the readiness check and the
     * base URL for the training run. The packaged application is driven over HTTP, so the
     * tests read this base URL from the {@code aot.training.url} system property. Defaults
     * to {@code http://localhost:8080/}.
     * @return the readiness URL property
     */
    public abstract Property<String> getReadyUrl();

    /**
     * The application start class used for training. When unset, it is read from the
     * packaged JAR's {@code Start-Class} manifest attribute.
     * @return the start-class property
     */
    public abstract Property<String> getStartClass();

    /**
     * An optional container image whose JVM records the cache. Set this when the cache must
     * be loaded by an image whose JVM differs from the build host (a different JDK
     * distribution or version, or a different architecture, which is the common case when
     * building on, for example, macOS arm64 and deploying a linux/amd64 image). The packaged
     * application then runs inside that image and the recorded cache matches the image's JVM
     * build and architecture.
     *
     * <p>When unset (the default), the packaged application records against the local JVM
     * instead. The cache is then only loadable by that same local JVM build and architecture,
     * so leave it unset only when the runtime JVM matches the build host.
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
     * Arguments passed to the application during training (for example, to set
     * a Spring profile).
     * @return the application-arguments property
     */
    public abstract ListProperty<String> getApplicationArguments();

    /**
     * Extra JVM options passed to the recording JVM (before {@code -cp}). An AOT cache only
     * loads when the runtime JVM is started with the same options it was recorded with, so
     * set these to whatever the runtime adds, for example
     * {@code listOf("--enable-native-access=ALL-UNNAMED")}. Applies to both the local and the
     * {@link #getContainerImage() container} recording JVM.
     *
     * <p>When unset, the options are derived from the {@code bootBuildImage} task's
     * environment ({@code JAVA_TOOL_OPTIONS} and {@code BPE_JDK_JAVA_OPTIONS}), so the image
     * build is the single source of truth and the flags need not be listed here as well. Set
     * this property explicitly to override that derivation.
     * @return the jvm-arguments property
     */
    public abstract ListProperty<String> getJvmArguments();

    /**
     * How long to wait for the application to become ready during training, in
     * seconds. Defaults to {@code 120}.
     * @return the start-timeout property
     */
    public abstract Property<Integer> getStartTimeout();

}
