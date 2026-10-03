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

plugins {
    `java-library`
    alias(libs.plugins.vanniktech.maven.publish)
}

description = "Core helpers for recording a JVM AOT cache (JEP 483 / JEP 514)"

dependencies {
    compileOnly(libs.jspecify)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
}

tasks.named<Test>("test") {
    // Lets a test assert that JUnitPlatformVersion.DEFAULT_PLATFORM_VERSION stays in step
    // with the version catalog.
    systemProperty("aot.test.junitPlatformVersion", libs.versions.junitPlatform.get())
}
