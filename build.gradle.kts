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
    base
    // Applied to each module below; declared here so the alias is on the build class path.
    alias(libs.plugins.vanniktech.maven.publish) apply false
    alias(libs.plugins.plugin.publish) apply false
}

description = "AOT cache training for integration tests"

// Aggregates publishing of every module to the shared local test repository; used by the
// Maven integration tests.
tasks.register("publishForIntegrationTests") {
    group = "verification"
    description = "Publishes all modules to the local test repository for integration tests"
    dependsOn(subprojects.map { "${it.path}:publishAllPublicationsToLocalTestRepository" })
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    group = rootProject.group
    version = rootProject.version

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
        withSourcesJar()
    }

    // Published bytecode targets an older release so the artifacts load on any JVM.
    // The AOT-cache listener itself only activates on JDK 25+, where the single-step
    // -XX:AOTCacheOutput flag is understood.
    tasks.withType<JavaCompile>().configureEach {
        options.release = 17
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    // Keep the published API documentation warning-free. Warnings are errors so a new
    // undocumented constructor or a broken tag fails the build instead of slipping through.
    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).apply {
            addStringOption("Xdoclint:all", "-quiet")
            addBooleanOption("Werror", true)
        }
    }

    tasks.named("check") {
        dependsOn("javadoc")
    }

    plugins.withId("maven-publish") {
        // The local test repository is used by the Maven plugin's integration tests, which
        // consume every module via -Dmaven.repo.local.
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "localTest"
                    url = uri(rootProject.layout.buildDirectory.dir("local-repo"))
                }
            }
        }
    }

    // Publishing metadata common to every module. Only populated when the vanniktech
    // Maven publish plugin is applied, which owns the publications and signing.
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set(project.name)
                    description.set(project.description)
                    url.set("https://github.com/vpelikh/aot-cache-training")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("vpelikh")
                            name.set("Vasily Pelikh")
                            url.set("https://github.com/vpelikh")
                        }
                    }
                    scm {
                        url.set("https://github.com/vpelikh/aot-cache-training")
                        connection.set("scm:git:https://github.com/vpelikh/aot-cache-training.git")
                        developerConnection.set("scm:git:ssh://git@github.com/vpelikh/aot-cache-training.git")
                    }
                }
            }
        }
    }
}
