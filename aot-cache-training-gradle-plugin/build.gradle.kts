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
    `java-gradle-plugin`
    `maven-publish`
}

description = "Gradle plugin that records a JVM AOT cache from integration tests"

dependencies {
    implementation(project(":aot-cache-training"))
    implementation(project(":aot-cache-training-trainer"))

    compileOnly(gradleApi())

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.assertj.core)
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("aotCacheTraining") {
            id = "io.github.vpelikh.aot-cache-training"
            displayName = "AOT cache training"
            description = "Records a JVM AOT cache from integration tests by injecting -XX:AOTCacheOutput"
            implementationClass = "io.github.vpelikh.aot.gradle.AotCacheTrainingPlugin"
        }
    }
}
