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
    `maven-publish`
    alias(libs.plugins.maven.plugin.development)
}

description = "Maven plugin that records a JVM AOT cache from integration tests"

extensions.configure<org.gradlex.maven.plugin.development.MavenPluginDevelopmentExtension> {
    goalPrefix.set("aot-cache-training")
}

dependencies {
    implementation(project(":aot-cache-training"))
    implementation(project(":aot-cache-training-trainer"))

    compileOnly(libs.maven.plugin.api)
    compileOnly(libs.maven.core)
    compileOnly(libs.maven.plugin.annotations)
    compileOnly(libs.maven.resolver.api)
    compileOnly("javax.inject:javax.inject:1")

    testImplementation(libs.maven.plugin.api)
    testImplementation(libs.maven.core)
    testImplementation(libs.maven.plugin.annotations)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
}

// The Maven descriptor generator (maven-plugin-tools 3.16) cannot read JDK 25 class
// files (major version 69), so compile this module to an older release. The published
// plugin still runs on JDK 25; only its bytecode target is older.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

// The end-to-end integration tests run a real Maven build that resolves this plugin and
// the library from the shared local repository, so publish them first.
tasks.named<Test>("test") {
    dependsOn(":publishForIntegrationTests")
}
