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

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Small Spring configuration used by the end-to-end recording test. The bean is
 * initialized eagerly so that its class and {@code @PostConstruct} work is part of the
 * training workload.
 *
 * @author Vasily Pelikh
 */
@Configuration(proxyBeanMethods = false)
public class TrainingApplication {

	@Bean
	public GreetingService greetingService() {
		return new GreetingService();
	}

	/**
	 * A trivial service whose initialization is captured during the training run.
	 */
	public static class GreetingService {

		public String greet(String name) {
			return "Hello, " + name + "!";
		}

	}

}