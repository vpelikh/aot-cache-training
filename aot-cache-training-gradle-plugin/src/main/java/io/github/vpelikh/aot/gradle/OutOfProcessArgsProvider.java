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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.gradle.api.provider.Provider;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * Contributes the out-of-process training launcher arguments lazily, so the extension
 * values are read when the task runs.
 *
 * @author Vasily Pelikh
 */
class OutOfProcessArgsProvider implements CommandLineArgumentProvider {

    private final AotCacheTrainingExtension extension;

    private final Provider<Path> appJar;

    private final Provider<Path> cacheFile;

    private final Provider<Path> layoutDirectory;

    OutOfProcessArgsProvider(AotCacheTrainingExtension extension, Provider<Path> appJar, Provider<Path> cacheFile,
            Provider<Path> layoutDirectory) {
        this.extension = extension;
        this.appJar = appJar;
        this.cacheFile = cacheFile;
        this.layoutDirectory = layoutDirectory;
    }

    @Override
    public Iterable<String> asArguments() {
        List<String> args = new ArrayList<>();
        args.add("--app-jar=" + this.appJar.get().toAbsolutePath());
        args.add("--cache=" + this.cacheFile.get().toAbsolutePath());
        args.add("--layout=" + this.layoutDirectory.get().toAbsolutePath());
        args.add("--ready-url=" + this.extension.getReadyUrl().get());
        String startClass = this.extension.getStartClass().getOrNull();
        if (startClass != null) {
            args.add("--start-class=" + startClass);
        }
        String image = this.extension.getContainerImage().getOrNull();
        if (image != null) {
            args.add("--image=" + image);
        }
        args.add("--container-runtime=" + this.extension.getContainerRuntime().get());
        args.add("--start-timeout=" + this.extension.getStartTimeout().get());
        for (String applicationArgument : this.extension.getApplicationArguments().getOrElse(List.of())) {
            args.add("--application-arg=" + applicationArgument);
        }
        for (String jvmArgument : this.extension.getJvmArguments().getOrElse(List.of())) {
            args.add("--jvm-arg=" + jvmArgument);
        }
        for (String packageName : this.extension.getPackagesToScan().getOrElse(List.of())) {
            args.add("--select-package=" + packageName);
        }
        if (!Boolean.TRUE.equals(this.extension.getFailOnTestFailure().getOrElse(true))) {
            args.add("--no-fail-on-test-failure");
        }
        if (Boolean.TRUE.equals(this.extension.getAllowEmptyWorkload().getOrElse(false))) {
            args.add("--allow-empty");
        }
        return args;
    }

}