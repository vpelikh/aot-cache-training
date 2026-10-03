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

package io.github.vpelikh.aot.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

/**
 * Tests for {@link AotCacheRecordMojo}.
 *
 * @author Vasily Pelikh
 */
class AotCacheRecordMojoTests {

    @Test
    void doesNothingWhenDisabled(@TempDir Path basedir) throws Exception {
        MavenProject project = project(basedir);
        AotCacheRecordMojo mojo = new AotCacheRecordMojo();
        mojo.setProject(project);
        mojo.setEnabled(false);

        mojo.execute();
    }

    @Test
    void resolvesCacheFileUnderBuildDirectory(@TempDir Path basedir) {
        AotCacheRecordMojo mojo = new AotCacheRecordMojo();
        mojo.setProject(project(basedir));

        Path cacheFile = mojo.resolveCacheFile();

        assertThat(cacheFile).isEqualTo(basedir.resolve("target").resolve("aot-cache").resolve("application.aot")
            .toAbsolutePath()
            .normalize());
    }

    @Test
    void skipsWhenSkipIsSet(@TempDir Path basedir) throws Exception {
        AotCacheRecordMojo mojo = new AotCacheRecordMojo();
        mojo.setProject(project(basedir));
        mojo.setEnabled(true);
        mojo.setSkip(true);

        mojo.execute();
    }

    @Test
    void buildsLauncherArguments(@TempDir Path basedir) throws Exception {
        Path appJar = basedir.resolve("target/app.jar");
        Files.createDirectories(appJar.getParent());
        writeBootJar(appJar, "com.example.Application");

        AotCacheRecordMojo mojo = new AotCacheRecordMojo();
        mojo.setProject(project(basedir));
        mojo.setEnabled(true);
        mojo.setApplicationJar(appJar.toString());
        mojo.setReadyUrl("http://localhost:8080/");
        mojo.setContainerImage("my-app:latest");
        mojo.setStartTimeout(90);
        mojo.setApplicationArguments(List.of("--spring.profiles.active=prod"));

        List<String> arguments = mojo.outOfProcessArguments(basedir.resolve("target/aot-cache/application.aot"));

        assertThat(arguments).contains("io.github.vpelikh.aot.trainer.OutOfProcessTrainingLauncher",
                "--app-jar=" + appJar.toAbsolutePath(), "--ready-url=http://localhost:8080/",
                "--image=my-app:latest", "--container-runtime=docker", "--start-timeout=90",
                "--application-arg=--spring.profiles.active=prod");
    }

    @Test
    void locatesPackagedApplicationJar(@TempDir Path basedir) throws Exception {
        Path appJar = basedir.resolve("target/app-1.0.0.jar");
        Files.createDirectories(appJar.getParent());
        writeBootJar(appJar, "com.example.Application");

        AotCacheRecordMojo mojo = new AotCacheRecordMojo();
        mojo.setProject(project(basedir));

        List<String> arguments = mojo.outOfProcessArguments(basedir.resolve("target/aot-cache/application.aot"));

        assertThat(arguments).contains("--app-jar=" + appJar.toAbsolutePath());
    }

    @Test
    void failsWhenNoPackagedApplicationJarExists(@TempDir Path basedir) throws Exception {
        Files.createDirectories(basedir.resolve("target"));

        AotCacheRecordMojo mojo = new AotCacheRecordMojo();
        mojo.setProject(project(basedir));

        assertThatIOException().isThrownBy(
                () -> mojo.outOfProcessArguments(basedir.resolve("target/aot-cache/application.aot")));
    }

    private void writeBootJar(Path jar, String startClass) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", "org.springframework.boot.loader.launch.JarLauncher");
        manifest.getMainAttributes().putValue("Start-Class", startClass);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("BOOT-INF/"));
            out.closeEntry();
        }
    }

    private MavenProject project(Path basedir) {
        MavenProject project = new MavenProject();
        project.setFile(basedir.resolve("pom.xml").toFile());
        Build build = new Build();
        build.setDirectory("target");
        project.getModel().setBuild(build);
        project.setBuild(build);
        return project;
    }

}
