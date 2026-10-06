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

// End-to-end tests that build the shipped examples with their own tool and verify the
// recorded AOT cache reaches and loads in the resulting container image. They live in
// their own module, not in either plugin module, because they cover both the Gradle and
// the Maven examples and belong to neither build tool in particular.
description = "End-to-end tests for the examples and their container images"

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
}

// The example builds resolve this project's modules from the shared local repository
// (Maven) or via includeBuild (Gradle), so publish everything first.
tasks.named<Test>("test") {
    dependsOn(":publishForIntegrationTests")
    // A Docker daemon and a JDK 25 toolchain are required; the tests skip themselves
    // (assumptions) when Docker is unavailable, and are enabled only on JDK 25+.
    systemProperty("aot.integration.repoRoot", rootProject.projectDir.absolutePath)
}