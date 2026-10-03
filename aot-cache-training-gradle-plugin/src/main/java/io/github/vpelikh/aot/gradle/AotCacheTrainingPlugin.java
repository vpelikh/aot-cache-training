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

package io.github.vpelikh.aot.gradle;

import java.io.File;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.vpelikh.aot.AotCache;
import io.github.vpelikh.aot.JUnitPlatformVersion;
import io.github.vpelikh.aot.trainer.OutOfProcessTrainingLauncher;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.jvm.tasks.Jar;

/**
 * Gradle plugin that records a JVM AOT cache (JEP 483 / JEP 514) from the project's
 * integration tests.
 *
 * <p>Apply the plugin and opt in:
 *
 * <pre>{@code
 * plugins {
 *     id 'io.github.vpelikh.aot-cache-training'
 * }
 *
 * aotCacheTraining {
 *     enabled = true
 * }
 * }</pre>
 *
 * <p>When enabled, the plugin:
 * <ol>
 * <li>packages the application into its boot JAR;</li>
 * <li>starts the packaged application in its own JVM through
 * {@code io.github.vpelikh.aot.trainer.OutOfProcessTrainingLauncher} with
 * {@code -XX:AOTCacheOutput=<buildDir>/aot-cache/application.aot} (optionally inside a
 * container image);</li>
 * <li>runs the integration tests as an external HTTP client of that application;</li>
 * <li>verifies through the {@code verifyAotCache} task that a non-empty cache was produced.</li>
 * </ol>
 *
 * <p>The cache is recorded against the packaged application's own class path, so it is
 * usable by the packaged application (including a container image built from it). This is
 * deliberately separate from the normal {@code test} task, which boots the application
 * in-process and cannot produce a cache the packaged application can load.
 *
 * @author Vasily Pelikh
 */
public class AotCacheTrainingPlugin implements Plugin<Project> {

    /**
     * Create the plugin. Gradle instantiates it when the plugin is applied.
     */
    public AotCacheTrainingPlugin() {
    }

    /**
     * The name of the task that records the cache.
     */
    public static final String RECORD_TASK_NAME = "aotCacheTraining";

    /**
     * The name of the verification task.
     */
    public static final String VERIFY_TASK_NAME = "verifyAotCache";

    /**
     * The name of the task that embeds the recorded cache into a copy of the boot JAR, so
     * {@code bootBuildImage} ships the cache inside the image.
     */
    public static final String EMBED_IMAGE_JAR_TASK_NAME = "aotCacheImageJar";

    private static final String GROUP = "aot";

    @Override
    public void apply(Project project) {
        AotCacheTrainingExtension extension = project.getExtensions()
            .create("aotCacheTraining", AotCacheTrainingExtension.class);
        extension.getEnabled().convention(false);
        extension.getFailOnTestFailure().convention(true);
        extension.getAllowEmptyWorkload().convention(false);
        extension.getReadyUrl().convention("http://localhost:8080/");
        extension.getContainerRuntime().convention("docker");
        extension.getApplicationArguments().convention(List.of());
        extension.getStartTimeout().convention(120);

        Path buildDirectory = project.getLayout().getBuildDirectory().get().getAsFile().toPath();
        Path cacheFile = AotCache.defaultCacheFile(buildDirectory);

        // The verify task is always registered so it exists regardless of plugin order.
        project.getTasks().register(VERIFY_TASK_NAME, (task) -> {
            task.setGroup(GROUP);
            task.setDescription("Verifies that the integration tests recorded a non-empty JVM AOT cache");
            task.doLast((unused) -> {
                if (!Boolean.TRUE.equals(extension.getEnabled().getOrElse(false))) {
                    return;
                }
                long size = AotCache.verifyRecordedCache(cacheFile);
                if (size <= 0) {
                    throw new IllegalStateException("AOT cache recording was enabled (aotCacheTraining.enabled = "
                            + "true) but no non-empty cache was found at " + cacheFile + ". Run on JDK "
                            + AotCache.MINIMUM_RECORDING_JDK
                            + "+ and make sure the training JVM exits cleanly (no System.exit mid-run).");
                }
            });
        });

        // Source sets and the jar task require the java plugin; react when it is applied so the
        // plugin can be listed before or after 'java' in the plugins block.
        project.getPlugins().withId("java", (java) -> configureJavaProject(project, extension, cacheFile));
    }

    private void configureJavaProject(Project project, AotCacheTrainingExtension extension, Path cacheFile) {
        TaskProvider<JavaExec> record = project.getTasks()
            .register(RECORD_TASK_NAME, JavaExec.class,
                    (task) -> configureTraining(project, extension, task, cacheFile));
        project.getTasks().named(RECORD_TASK_NAME, JavaExec.class).configure((task) -> task
            .onlyIf("AOT cache recording is enabled", (unused) -> Boolean.TRUE.equals(extension.getEnabled().getOrElse(false))));

        // Verification requires the recording task.
        project.getTasks().named(VERIFY_TASK_NAME).configure((task) -> task.dependsOn(record));

        // Embed the cache into a copy of the boot JAR and point bootBuildImage at it, so the
        // image build picks the cache up without any manual jar-append step. Registered
        // lazily and applied only when the Spring Boot plugin's bootBuildImage exists.
        project.getPlugins().withId("org.springframework.boot", (bootPlugin) -> {
            TaskProvider<EmbedAotCacheTask> embed = project.getTasks()
                .register(EMBED_IMAGE_JAR_TASK_NAME, EmbedAotCacheTask.class, (task) -> {
                    task.setGroup(GROUP);
                    task.setDescription("Embeds the recorded AOT cache into a copy of the boot JAR for the image build");
                    task.dependsOn(record, "bootJar");
                    task.onlyIf("AOT cache recording is enabled",
                            (unused) -> Boolean.TRUE.equals(extension.getEnabled().getOrElse(false)));
                    task.getBootJar().set(project.getTasks().named("bootJar", Jar.class)
                        .flatMap((jar) -> jar.getArchiveFile()));
                    task.getCacheFile().set(cacheFile.toFile());
                    task.getOutputJar().set(project.getLayout()
                        .getBuildDirectory()
                        .file("aot-cache-image/application.jar"));
                });
            project.getTasks().named("bootBuildImage").configure((buildImage) -> {
                buildImage.dependsOn(embed);
                // BootBuildImage is not a compile dependency (the Spring Boot plugin is
                // optional), so its properties are set reflectively:
                //  - point the image build at the cache-embedded JAR;
                //  - enable the buildpack's AOT-cache mode, which is what makes it look for a
                //    pre-recorded aot-cache/application.aot instead of running its own training.
                try {
                    Class<?> buildImageType = buildImage.getClass();
                    RegularFileProperty archiveFile = (RegularFileProperty) buildImageType
                        .getMethod("getArchiveFile")
                        .invoke(buildImage);
                    archiveFile.set(embed.flatMap(EmbedAotCacheTask::getOutputJar));
                    @SuppressWarnings("unchecked")
                    MapProperty<String, String> environment = (MapProperty<String, String>) buildImageType
                        .getMethod("getEnvironment")
                        .invoke(buildImage);
                    environment.put("BP_JVM_AOTCACHE_ENABLED", "true");
                }
                catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("Unable to configure bootBuildImage for the AOT cache", ex);
                }
            });
        });
    }

    private void configureTraining(Project project, AotCacheTrainingExtension extension, JavaExec task, Path cacheFile) {
        task.setGroup(GROUP);
        task.setDescription("Records a JVM AOT cache from the packaged application driven by the integration tests");

        // Run the training JVM on the project's Java toolchain (defaulting to the recording
        // minimum, JDK 25), so recording does not depend on whichever JVM runs Gradle. The
        // packaged application is started with this same JVM unless containerImage is set.
        task.getJavaLauncher()
            .set(project.getProviders().provider(() -> {
                JavaLanguageVersion version = project.getExtensions()
                    .getByType(JavaPluginExtension.class)
                    .getToolchain()
                    .getLanguageVersion()
                    .getOrElse(JavaLanguageVersion.of(AotCache.MINIMUM_RECORDING_JDK));
                return project.getExtensions()
                    .getByType(JavaToolchainService.class)
                    .launcherFor((spec) -> spec.getLanguageVersion().set(version))
                    .get();
            }));
        task.doFirst("check the training JDK", (unused) -> {
            int feature = task.getJavaLauncher()
                .get()
                .getMetadata()
                .getLanguageVersion()
                .asInt();
            if (feature < AotCache.MINIMUM_RECORDING_JDK) {
                throw new IllegalStateException("AOT cache recording requires JDK " + AotCache.MINIMUM_RECORDING_JDK
                        + " or later, but the training JVM is JDK " + feature
                        + ". Configure a Java 25+ toolchain for the project.");
            }
        });

        SourceSet test = project.getExtensions()
            .getByType(SourceSetContainer.class)
            .getByName(SourceSet.TEST_SOURCE_SET_NAME);
        task.getMainClass().set("io.github.vpelikh.aot.trainer.OutOfProcessTrainingLauncher");
        // The test JVM only runs the client tests; the packaged application records the
        // cache in its own JVM, so the test class path may contain directories.
        ConfigurableFileCollection classpath = project.getObjects()
            .fileCollection();
        classpath.from(test.getRuntimeClasspath());
        // The launcher and its core helpers live on the plugin's class path, because the
        // plugin depends on the trainer module. They carry no JUnit. The JUnit Platform
        // generation comes from the project; the launcher itself (which junit-jupiter does
        // not bring) is resolved at the project's own platform version so the launcher API
        // and the test engine are never mixed across generations.
        classpath.from(project.getProviders().provider(() -> {
            List<File> launcher = new ArrayList<>();
            launcher.add(launcherJar(AotCache.class));
            launcher.add(launcherJar(OutOfProcessTrainingLauncher.class));
            boolean projectHasLauncher = test.getRuntimeClasspath()
                .getFiles()
                .stream()
                .anyMatch((file) -> file.getName().startsWith(JUnitPlatformVersion.LAUNCHER_FILE_PREFIX));
            if (!projectHasLauncher) {
                launcher.addAll(resolveLauncher(project, test));
            }
            return launcher.stream().distinct().toList();
        }));
        task.setClasspath(classpath);
        task.getArgumentProviders().add(new AotCacheArgsProvider(extension));

        // Resolve the packaged application JAR lazily so the plugin does not force task
        // realization at configuration time. The application process records the cache, so
        // the test JVM must NOT record: two JVMs writing the same cache file would clobber
        // each other, and the test class path is not the class path the cache must match.
        task.dependsOn("bootJar");
        Provider<Path> appJar = project.getProviders().provider(() -> {
            Task bootJarTask = project.getTasks().findByName("bootJar");
            if (!(bootJarTask instanceof Jar)) {
                throw new IllegalStateException("Out-of-process AOT cache training needs the Spring Boot "
                        + "plugin's 'bootJar' task, which was not found. Apply the Spring Boot plugin so the "
                        + "packaged application JAR exists.");
            }
            return ((Jar) bootJarTask).getArchiveFile().get().getAsFile().toPath();
        });
        Provider<Path> layout = project.getProviders()
            .provider(() -> cacheFile.getParent().resolve("app-layout"));
        task.getArgumentProviders()
            .add(new OutOfProcessArgsProvider(extension, appJar, project.getProviders().provider(() -> cacheFile),
                    layout));
        task.getOutputs().file(cacheFile.toFile());
    }

    /**
     * Return the JAR that declares the given class, on the plugin's own class path.
     * @param type a class from the component
     * @return the JAR or classes directory
     */
    private static File launcherJar(Class<?> type) {
        try {
            CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                throw new IllegalStateException("No code source for " + type.getName());
            }
            return new File(codeSource.getLocation().toURI());
        }
        catch (Exception ex) {
            throw new IllegalStateException("Unable to locate the code source for " + type.getName(), ex);
        }
    }

    /**
     * Resolve the JUnit Platform launcher at the project's own JUnit Platform version, so the
     * launcher API and the test engine belong to the same generation. The version is taken
     * from the resolved test dependency coordinates (falling back to JAR names, then to the
     * version this plugin was built against).
     * @param project the project
     * @param test the test source set
     * @return the launcher JAR(s)
     */
    private static List<File> resolveLauncher(Project project, SourceSet test) {
        String version = JUnitPlatformVersion.fromCoordinates(dependencyCoordinates(project, test))
            .or(() -> JUnitPlatformVersion.find(test.getRuntimeClasspath().getFiles().stream().map(File::toPath).toList()))
            .orElse(JUnitPlatformVersion.DEFAULT_PLATFORM_VERSION);
        Configuration configuration = project.getConfigurations()
            .detachedConfiguration(project.getDependencies().create(JUnitPlatformVersion.LAUNCHER_COORDINATE + ":" + version));
        configuration.setTransitive(true);
        return new ArrayList<>(configuration.resolve());
    }

    /**
     * Return the resolved {@code group:artifact} to version map for the test runtime
     * configuration, so the JUnit Platform version can be read from dependency metadata
     * rather than file names.
     * @param project the project
     * @param test the test source set
     * @return the coordinate map
     */
    private static Map<String, String> dependencyCoordinates(Project project, SourceSet test) {
        Map<String, String> coordinates = new LinkedHashMap<>();
        Configuration configuration = project.getConfigurations()
            .getByName(test.getRuntimeClasspathConfigurationName());
        for (ResolvedArtifact artifact : configuration.getResolvedConfiguration()
            .getResolvedArtifacts()) {
            ModuleVersionIdentifier id = artifact.getModuleVersion().getId();
            coordinates.putIfAbsent(id.getGroup() + ":" + id.getName(), id.getVersion());
        }
        return coordinates;
    }

}
