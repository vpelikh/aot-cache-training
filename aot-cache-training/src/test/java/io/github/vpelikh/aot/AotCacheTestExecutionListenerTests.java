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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.core.SpringProperties;
import org.springframework.test.context.TestContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link AotCacheTestExecutionListener}.
 *
 * @author Vasily Pelikh
 */
class AotCacheTestExecutionListenerTests {

	private final AotCacheTestExecutionListener listener = new AotCacheTestExecutionListener();

	@AfterEach
	void clearProperties() {
		SpringProperties.setProperty(DefaultLifecycleProcessor.EXIT_PROPERTY_NAME, null);
	}

	@Test
	void orderValue() {
		assertThat(listener.getOrder()).isEqualTo(AotCacheTestExecutionListener.ORDER);
	}

	@Test
	void beforeTestClassWhenRecordingIsNotEnabled() throws Exception {
		TestContext testContext = mock();
		listener.beforeTestClass(testContext);
		verify(testContext, never()).getApplicationContext();
	}

	@Test
	void beforeTestClassWhenRecordingIsEnabledInitializesContext() throws Exception {
		ApplicationContext applicationContext = mock();
		TestContext testContext = mock();
		given((Class) testContext.getTestClass()).willReturn((Class) AotCacheTestExecutionListenerTests.class);
		given(testContext.getApplicationContext()).willReturn(applicationContext);

		listenerWithArgs("-XX:AOTCacheOutput=build/app.aot").beforeTestClass(testContext);

		verify(testContext).getApplicationContext();
	}

	@Test
	void beforeTestClassWhenJdkVersionIsUnsupported() {
		AotCacheTestExecutionListener unsupportedListener = new AotCacheTestExecutionListener() {
			@Override
			protected List<String> getInputArguments() {
				return List.of("-XX:AOTCacheOutput=build/app.aot");
			}

			@Override
			protected int getRequiredJavaFeatureVersion() {
				return 9999;
			}
		};
		TestContext testContext = mock();
		given((Class) testContext.getTestClass()).willReturn((Class) AotCacheTestExecutionListenerTests.class);

		assertThatIllegalStateException().isThrownBy(() -> unsupportedListener.beforeTestClass(testContext))
			.withMessageContaining("JDK");
		verify(testContext, never()).getApplicationContext();
	}

	@Test
	void beforeTestClassWhenClassLoaderIsNotStandardStillInitializesContext() throws Exception {
		AotCacheTestExecutionListener warnListener = new AotCacheTestExecutionListener() {
			@Override
			protected List<String> getInputArguments() {
				return List.of("-XX:AOTCacheOutput=build/app.aot");
			}

			@Override
			protected boolean isStandardClassLoader(ClassLoader classLoader) {
				return false;
			}
		};
		ApplicationContext applicationContext = mock();
		given(applicationContext.getClassLoader()).willReturn(AotCacheTestExecutionListenerTests.class.getClassLoader());
		TestContext testContext = mock();
		given((Class) testContext.getTestClass()).willReturn((Class) AotCacheTestExecutionListenerTests.class);
		given(testContext.getApplicationContext()).willReturn(applicationContext);

		warnListener.beforeTestClass(testContext);

		verify(testContext).getApplicationContext();
	}

	@Test
	void isExitOnRefreshConfiguredWhenPropertyIsSet() {
		SpringProperties.setProperty(DefaultLifecycleProcessor.EXIT_PROPERTY_NAME, "onRefresh");
		assertThat(listener.isExitOnRefreshConfigured()).isTrue();
	}

	@Test
	void isExitOnRefreshConfiguredWhenPropertyIsNotSet() {
		assertThat(listener.isExitOnRefreshConfigured()).isFalse();
	}

	private AotCacheTestExecutionListener listenerWithArgs(String... args) {
		List<String> inputArgs = List.of(args);
		return new AotCacheTestExecutionListener() {
			@Override
			protected List<String> getInputArguments() {
				return inputArgs;
			}
		};
	}

}