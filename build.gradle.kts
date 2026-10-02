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
		// java-gradle-plugin already registers a 'pluginMaven' publication, so only add a
		// custom publication when one is not already present. Checked after evaluation
		// because plugin application order is not guaranteed.
		afterEvaluate {
			if (!plugins.hasPlugin("java-gradle-plugin")) {
				extensions.configure<PublishingExtension> {
					publications {
						create<MavenPublication>(project.name) {
							groupId = rootProject.group.toString()
							artifactId = project.name
							version = project.version.toString()
							from(components["java"])
							pom {
								name.set(project.name)
								description.set(project.description)
								licenses {
									license {
										name.set("Apache License, Version 2.0")
										url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
									}
								}
							}
						}
					}
				}
			}
		}
		extensions.configure<PublishingExtension> {
			repositories {
				maven {
					name = "localTest"
					// A single shared repository at the root, so the Maven integration tests
					// can consume every module via -Dmaven.repo.local.
					url = uri(rootProject.layout.buildDirectory.dir("local-repo"))
				}
			}
		}
	}
}