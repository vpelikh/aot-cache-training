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
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

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
    void thisJarDeclaresTheListener() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/META-INF/spring.factories")) {
            assertThat(input).as("META-INF/spring.factories must be on the class path").isNotNull();
            Properties properties = new Properties();
            properties.load(input);
            String value = properties.getProperty(TestExecutionListener.class.getName());
            assertThat(value).isNotNull().contains(AotCacheTestExecutionListener.class.getName());
        }
    }

    @Test
    void springDiscoversTheListenerAmongClasspathFactories() throws IOException {
        // Mirror how the SpringFactoriesLoader finds properties files: scan every
        // META-INF/spring.factories on the class path and confirm ours registers the
        // listener under the TestExecutionListener key.
        Enumeration<URL> resources = getClass().getClassLoader().getResources("META-INF/spring.factories");
        List<String> declared = new ArrayList<>();
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            Properties properties = new Properties();
            try (InputStream input = url.openStream()) {
                properties.load(input);
            }
            String value = properties.getProperty(TestExecutionListener.class.getName());
            if (value != null) {
                declared.addAll(List.of(value.split(",")));
            }
        }
        assertThat(declared).map(String::trim)
            .as("AotCacheTestExecutionListener should be registered by default")
            .contains(AotCacheTestExecutionListener.class.getName());
    }

}
