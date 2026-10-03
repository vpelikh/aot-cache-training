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

import java.io.IOException;

import io.github.vpelikh.aot.AotCache;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Copies a Spring Boot application JAR and adds the recorded AOT cache as an
 * {@code aot-cache/application.aot} entry, so {@code bootBuildImage} ships the cache.
 *
 * <p>The embedding (entry layout and POSIX modes) is performed by
 * {@link AotCache#embedCacheIntoJar}; see there for why the modes matter.
 *
 * @author Vasily Pelikh
 */
@CacheableTask
public abstract class EmbedAotCacheTask extends DefaultTask {

    /**
     * Create the task. Gradle instantiates it and injects the managed properties.
     */
    public EmbedAotCacheTask() {
    }

    /**
     * The Spring Boot application JAR to copy.
     * @return the boot JAR property
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBootJar();

    /**
     * The recorded AOT cache to embed.
     * @return the cache file property
     */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getCacheFile();

    /**
     * The application JAR with the cache embedded.
     * @return the output JAR property
     */
    @OutputFile
    public abstract RegularFileProperty getOutputJar();

    /**
     * Copy the boot JAR and append the cache entry.
     * @throws IOException if the JARs cannot be read or written
     */
    @TaskAction
    public void embed() throws IOException {
        AotCache.embedCacheIntoJar(getBootJar().get().getAsFile().toPath(),
                getCacheFile().get().getAsFile().toPath(),
                getOutputJar().get().getAsFile().toPath());
        getLogger().lifecycle("Embedded the AOT cache into {}", getOutputJar().get().getAsFile());
    }

}