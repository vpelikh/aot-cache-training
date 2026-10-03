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

description = "Entry point that starts the packaged application and drives it with tests to record an AOT cache"

dependencies {
    api(project(":aot-cache-training"))

    // The JUnit Platform launcher API is needed to compile the launcher, but it is
    // deliberately NOT a transitive dependency: the training class path must use the JUnit
    // Platform generation that the target project already uses, resolved by the build
    // plugins at run time. Shipping our own generation here would clash with the project's
    // (for example, a JUnit 5 project versus our JUnit 6 Platform).
    //
    // Compiled against JUnit Platform 6, which is API-compatible with the 5.x line at run
    // time, so the launcher works for projects on either generation.
    compileOnly(libs.junit.platform.launcher)
    compileOnly(libs.junit.platform.engine)
    compileOnly(libs.junit.platform.commons)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
}
