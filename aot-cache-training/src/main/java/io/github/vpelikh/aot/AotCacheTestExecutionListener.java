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

package io.github.vpelikh.aot;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.core.SpringProperties;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * {@code TestExecutionListener} that coordinates JVM AOT cache recording during a Spring
 * integration-test run, as originally proposed in
 * <a href="https://github.com/spring-projects/spring-framework/issues/36774">spring-framework
 * issue #36774</a> and maintained here as a standalone library.
 *
 * <p>The listener is registered by default through this library's
 * {@code META-INF/spring.factories} and activates automatically when the test JVM is
 * started with the JDK 25+ single-step {@code -XX:AOTCacheOutput=<path>} flag (see
 * {@link AotCache#OUTPUT_FLAG}). Injecting that flag is the responsibility of the build
 * tooling, since JVM flags can only be configured at startup.
 *
 * <p>When active, this listener:
 * <ul>
 * <li>validates that the runtime JDK supports recording;</li>
 * <li>eagerly initializes the {@link ApplicationContext} so that context creation, bean
 * instantiation and {@code @PostConstruct} callbacks are captured as part of the training
 * workload;</li>
 * <li>warns when the application context uses a non-standard class loader (the AOT cache
 * only caches classes loaded by JDK built-in class loaders);</li>
 * <li>warns when {@code -Dspring.context.exit=onRefresh} is set, since it would terminate
 * the test JVM before the cache is assembled.</li>
 * </ul>
 *
 * <p>This listener performs no recording itself: the JVM does the work. It is therefore
 * safe to leave registered regardless of whether recording is enabled; when the flag is
 * absent, every callback returns immediately.
 *
 * <p><strong>Note:</strong> the listener deliberately does <em>not</em> try to verify the
 * cache file from a JVM shutdown hook. The JVM assembles the final cache only
 * <em>after</em> shutdown hooks have run, so such a check would always (incorrectly)
 * report a missing cache. Verification is the responsibility of the build tooling, which
 * can inspect the file after the test JVM has exited; see
 * {@link AotCache#verifyRecordedCache(java.nio.file.Path)}.
 *
 * @author Vasily Pelikh
 * @see <a href="https://openjdk.org/jeps/483">JEP 483: Ahead-of-Time Class Loading &amp; Linking</a>
 * @see <a href="https://openjdk.org/jeps/514">JEP 514: Ahead-of-Time Command-Line Ergonomics</a>
 */
public class AotCacheTestExecutionListener extends AbstractTestExecutionListener {

    /**
     * Create a listener that prepares the training workload when AOT cache recording is
     * enabled. Registration is a no-op otherwise.
     */
    public AotCacheTestExecutionListener() {
    }

    /**
     * The {@link #getOrder() order} value for this listener. Ordered after
     * {@code CommonCachesTestExecutionListener} (3005) and before
     * {@code TransactionalTestExecutionListener} (4000).
     */
    public static final int ORDER = 3006;

    private static final Log logger = LogFactory.getLog(AotCacheTestExecutionListener.class);

    @Override
    public final int getOrder() {
        return ORDER;
    }

    @Override
    public void beforeTestClass(TestContext testContext) throws Exception {
        List<String> jvmArguments = getInputArguments();
        if (!AotCache.isRecordingEnabled(jvmArguments)) {
            return;
        }
        if (logger.isInfoEnabled()) {
            logger.info("AOT cache recording is enabled. Preparing the training workload for test class ["
                    + testContext.getTestClass().getName() + "].");
        }

        validateJdkVersion();

        // Initialize the ApplicationContext eagerly so that the training workload from
        // context creation (bean instantiation, @PostConstruct callbacks, etc.) is captured
        // by the JVM's AOT cache mechanism.
        ApplicationContext context = testContext.getApplicationContext();
        validateClassLoader(context);

        warnIfExitOnRefresh();

        String outputPath = AotCache.findOutputPath(jvmArguments);
        if (outputPath != null) {
            logger.info("The JVM will assemble the AOT cache at: " + outputPath
                    + " (on clean exit, after shutdown hooks).");
        }
    }

    /**
     * Validate that the running JDK supports AOT cache recording.
     * @throws IllegalStateException if the JDK version is unsupported
     */
    protected void validateJdkVersion() {
        int requiredVersion = getRequiredJavaFeatureVersion();
        int currentVersion = Runtime.version().feature();
        if (currentVersion < requiredVersion) {
            throw new IllegalStateException(
                    String.format("AOT cache recording requires JDK %d or later (JEP 514). Current JDK version: %d",
                            requiredVersion, currentVersion));
        }
    }

    /**
     * Validate that the given application context uses a standard JDK class loader.
     * @param context the application context
     */
    protected void validateClassLoader(ApplicationContext context) {
        ClassLoader classLoader = context.getClassLoader();
        if (classLoader != null && !isStandardClassLoader(classLoader) && logger.isWarnEnabled()) {
            logger.warn(String.format("""
                    The ApplicationContext class loader [%s] is not a standard JDK class loader.
                    An AOT cache only caches classes loaded by JDK built-in class loaders (JEP 483).
                    Use an extracted JAR layout with the standard class loader (for example, \
                    Spring Boot's executable JAR unpacking) for the cache to be effective.""",
                    classLoader.getClass().getName()));
        }
    }

    /**
     * Determine whether the {@code -Dspring.context.exit=onRefresh} flag is configured.
     * @return {@code true} if the flag is set to {@code onRefresh}
     */
    protected boolean isExitOnRefreshConfigured() {
        return "onRefresh".equalsIgnoreCase(SpringProperties.getProperty(DefaultLifecycleProcessor.EXIT_PROPERTY_NAME));
    }

    private void warnIfExitOnRefresh() {
        if (isExitOnRefreshConfigured() && logger.isWarnEnabled()) {
            logger.warn("The '" + DefaultLifecycleProcessor.EXIT_PROPERTY_NAME + "=onRefresh' property is set. "
                    + "This terminates the JVM when the ApplicationContext refreshes and is not compatible "
                    + "with generating an AOT cache from integration tests. Remove it from the test JVM arguments.");
        }
    }

    /**
     * Return the minimum JDK feature version required for AOT cache recording.
     * <p>Overridable in tests to simulate unsupported JDK versions.
     * @return the required JDK feature version (default: {@value AotCache#MINIMUM_RECORDING_JDK})
     */
    protected int getRequiredJavaFeatureVersion() {
        return AotCache.MINIMUM_RECORDING_JDK;
    }

    /**
     * Return {@code true} if the given class loader is a standard JDK class loader.
     * @param classLoader the class loader to check (never {@code null})
     * @return {@code true} if the class loader is a standard JDK class loader
     */
    protected boolean isStandardClassLoader(ClassLoader classLoader) {
        return classLoader.getClass().getName().startsWith("jdk.internal.loader.");
    }

    /**
     * Return the JVM command-line arguments, excluding arguments passed to the main method.
     * <p>Exposed for testing purposes.
     * @return the JVM command-line arguments
     */
    protected List<String> getInputArguments() {
        return AotCache.currentJvmArguments();
    }
}
