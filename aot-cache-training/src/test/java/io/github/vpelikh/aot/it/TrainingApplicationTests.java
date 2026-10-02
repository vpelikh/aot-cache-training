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

package io.github.vpelikh.aot.it;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A Spring integration test used as the training workload for the end-to-end recording
 * test. It is executed in a forked JVM started with {@code -XX:AOTCacheOutput}.
 *
 * @author Vasily Pelikh
 */
@SpringJUnitConfig(TrainingApplication.class)
class TrainingApplicationTests {

    @Test
    void greetingServiceIsWired(@Autowired TrainingApplication.GreetingService greetingService) {
        assertThat(greetingService.greet("AOT")).isEqualTo("Hello, AOT!");
    }

}
