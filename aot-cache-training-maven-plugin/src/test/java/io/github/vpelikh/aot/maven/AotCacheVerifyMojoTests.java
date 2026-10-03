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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for {@link AotCacheVerifyMojo}.
 *
 * @author Vasily Pelikh
 */
class AotCacheVerifyMojoTests {

    @Test
    void doesNothingWhenDisabled(@TempDir Path basedir) throws Exception {
        AotCacheVerifyMojo mojo = mojo(basedir);
        mojo.setEnabled(false);
        mojo.execute();
    }

    @Test
    void failsClosedWhenEnabledAndCacheMissing(@TempDir Path basedir) {
        AotCacheVerifyMojo mojo = mojo(basedir);
        mojo.setEnabled(true);

        assertThatExceptionOfType(MojoExecutionException.class).isThrownBy(mojo::execute)
            .withMessageContaining("no non-empty cache");
    }

    @Test
    void failsClosedWhenEnabledAndCacheEmpty(@TempDir Path basedir) throws Exception {
        AotCacheVerifyMojo mojo = mojo(basedir);
        mojo.setEnabled(true);
        Path cacheFile = mojo.resolveCacheFile();
        Files.createDirectories(cacheFile.getParent());
        Files.createFile(cacheFile);

        assertThatExceptionOfType(MojoExecutionException.class).isThrownBy(mojo::execute)
            .withMessageContaining("no non-empty cache");
    }

    @Test
    void passesWhenCacheRecorded(@TempDir Path basedir) throws Exception {
        AotCacheVerifyMojo mojo = mojo(basedir);
        mojo.setEnabled(true);
        Path cacheFile = mojo.resolveCacheFile();
        Files.createDirectories(cacheFile.getParent());
        Files.writeString(cacheFile, "recorded-cache-bytes");

        mojo.execute();

        assertThat(cacheFile).exists();
    }

    private AotCacheVerifyMojo mojo(Path basedir) {
        MavenProject project = new MavenProject();
        project.setFile(basedir.resolve("pom.xml").toFile());
        Build build = new Build();
        build.setDirectory("target");
        project.getModel().setBuild(build);
        project.getModel().setProperties(new Properties());
        project.setBuild(build);
        AotCacheVerifyMojo mojo = new AotCacheVerifyMojo();
        mojo.setProject(project);
        return mojo;
    }

}
