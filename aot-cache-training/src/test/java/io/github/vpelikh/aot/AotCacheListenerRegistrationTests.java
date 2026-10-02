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

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.test.context.TestExecutionListener;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the listener is registered by default through the TestContext framework's
 * {@code spring.factories} mechanism, without any user configuration.
 *
 * @author Vasily Pelikh
 */
class AotCacheListenerRegistrationTests {

	@Test
	void listenerIsListedInSpringFactories() throws IOException {
		try (InputStream input = getClass().getResourceAsStream("/META-INF/spring.factories")) {
			assertThat(input).as("META-INF/spring.factories must be on the class path").isNotNull();
			Properties properties = new Properties();
			properties.load(input);
			String value = properties.getProperty(TestExecutionListener.class.getName());
			assertThat(value).isNotNull().contains(AotCacheTestExecutionListener.class.getName());
		}
	}

	@Test
	void listenerIsListedForTheTestContextFramework() {
		var names = SpringFactoriesLoader.loadFactoryNames(TestExecutionListener.class,
				getClass().getClassLoader());
		assertThat(names)
			.as("AotCacheTestExecutionListener should be registered by default")
			.contains(AotCacheTestExecutionListener.class.getName());
	}

}