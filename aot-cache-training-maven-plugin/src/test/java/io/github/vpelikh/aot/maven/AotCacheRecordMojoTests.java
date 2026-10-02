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

import java.nio.file.Path;

import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

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
