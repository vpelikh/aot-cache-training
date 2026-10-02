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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.vpelikh.aot.AotCache;
import io.github.vpelikh.aot.JUnitPlatformVersion;
import io.github.vpelikh.aot.trainer.TrainingClasspath;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Records a JVM AOT cache (JEP 483 / JEP 514) from the project's tests.
 *
 * <p>This goal runs the tests through
 * <code>io.github.vpelikh.aot.trainer.TrainingLauncher</code> in a forked JVM started with
 * <code>-XX:AOTCacheOutput=&lt;build&gt;/aot-cache/application.aot</code>. The JVM assembles
 * the cache on clean exit.
 *
 * <p>The application and test classes are packaged into JARs first: the JVM refuses to
 * record a cache when the class path contains a non-empty directory
 * (<code>Cannot have non-empty directory in paths</code>).
 *
 * <p>Enable via the <code>aot.cache.record</code> property or the <code>enabled</code>
 * parameter. By default the goal is bound to the <code>process-test-classes</code> phase,
 * after the test classes have been compiled.
 *
 * <p>Set <code>outOfProcess</code> to record a cache the container image can load: the
 * packaged application runs in its own JVM (optionally inside <code>containerImage</code>)
 * and the tests drive it over HTTP (see
 * <code>io.github.vpelikh.aot.trainer.OutOfProcessTrainingLauncher</code>). This mode needs
 * the repackaged application JAR, so run the <code>package</code> phase first (bind the
 * goal to <code>verify</code>, or run <code>mvn package aot-cache-training:record</code>).
 *
 * @author Vasily Pelikh
 */
@Mojo(name = "record", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES,
        requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.TEST, threadSafe = true)
public class AotCacheRecordMojo extends AbstractMojo {

    /**
     * Create the mojo. Maven instantiates it and injects the configured parameters.
     */
    public AotCacheRecordMojo() {
    }

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @javax.inject.Inject
    private org.eclipse.aether.RepositorySystem repositorySystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private org.eclipse.aether.RepositorySystemSession repositorySystemSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private java.util.List<org.eclipse.aether.repository.RemoteRepository> remoteRepositories;

    /**
     * Whether to record an AOT cache. Can also be set with <code>-Daot.cache.record=true</code>.
     */
    @Parameter(property = "aot.cache.record", defaultValue = "false")
    private boolean enabled;

    /**
     * Skip execution entirely. Can also be set with <code>-Daot.cache.skip=true</code>.
     */
    @Parameter(property = "aot.cache.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Directory that holds the recorded cache, relative to the project build directory.
     */
    @Parameter(defaultValue = "aot-cache")
    private String cacheDirectory = AotCache.CACHE_DIRECTORY;

    /**
     * Packages whose tests form the training workload. When empty, the whole test class path
     * is scanned.
     */
    @Parameter
    private List<String> packagesToScan = new ArrayList<>();

    /**
     * Whether a failing test should fail the build. Defaults to <code>true</code>.
     */
    @Parameter(defaultValue = "true")
    private boolean failOnTestFailure = true;

    /**
     * Whether to record a cache even when the training run discovers no tests. Defaults to
     * <code>false</code>, because an empty workload records a large but useless cache.
     */
    @Parameter(defaultValue = "false")
    private boolean allowEmptyWorkload = false;

    /**
     * Path to the <code>java</code> executable used for the training run. Defaults to the JVM
     * running Maven. Must be JDK <code>AotCache.MINIMUM_RECORDING_JDK</code> (25) or later.
     */
    @Parameter(property = "aot.cache.trainingJvm")
    private String trainingJvm;

    /**
     * Whether to record the cache out of process: the packaged application runs in its own
     * JVM (optionally inside <code>containerImage</code>) and the tests drive it over HTTP, so
     * the recorded class path <em>is</em> the runtime class path. Required to produce a
     * cache the container image can load. Defaults to <code>false</code> (in-process).
     */
    @Parameter(property = "aot.cache.outOfProcess", defaultValue = "false")
    private boolean outOfProcess;

    /**
     * The packaged application JAR used for out-of-process training. When unset, the
     * repackaged Spring Boot JAR in the build directory is located automatically.
     */
    @Parameter(property = "aot.cache.applicationJar")
    private String applicationJar;

    /**
     * A URL polled until it returns a 2xx/3xx response, used as the readiness check and the
     * base URL for out-of-process training. Defaults to <code>http://localhost:8080/</code>.
     */
    @Parameter(defaultValue = "http://localhost:8080/")
    private String readyUrl = "http://localhost:8080/";

    /**
     * The application start class used for out-of-process training. When unset, it is read
     * from the packaged JAR's <code>Start-Class</code> manifest attribute.
     */
    @Parameter
    private String startClass;

    /**
     * A container image whose JVM records the out-of-process cache. When set, the packaged
     * application runs inside that image, so the recorded cache matches the image's JVM
     * build and architecture and can be loaded by that image at runtime.
     */
    @Parameter
    private String containerImage;

    /**
     * The container runtime executable used when <code>containerImage</code> is set. Defaults to
     * <code>docker</code>.
     */
    @Parameter(defaultValue = "docker")
    private String containerRuntime = "docker";

    /**
     * Arguments passed to the application during out-of-process training (for example, to
     * set a Spring profile).
     */
    @Parameter
    private List<String> applicationArguments = new ArrayList<>();

    /**
     * How long to wait for the application to become ready during out-of-process training,
     * in seconds. Defaults to <code>120</code>.
     */
    @Parameter(defaultValue = "120")
    private int startTimeout = 120;

    @Override
    public void execute() throws MojoExecutionException {
        if (this.skip) {
            getLog().debug("AOT cache recording is skipped (aot.cache.skip=true).");
            return;
        }
        if (!this.enabled) {
            getLog().debug("AOT cache recording is not enabled (aot.cache.record is not true).");
            return;
        }
        Path cacheFile = resolveCacheFile();
        Path workDirectory = cacheFile.getParent().resolve("classpath");
        checkTrainingJdk();
        try {
            Files.createDirectories(workDirectory);
            List<String> command = buildCommand(cacheFile, workDirectory);
            getLog().info("Recording AOT cache to " + cacheFile + " (JAR-only class path).");
            if (getLog().isDebugEnabled()) {
                getLog().debug("Training command: " + String.join(" ", command));
            }
            int exitCode = run(command);
            if (exitCode != 0) {
                throw new MojoExecutionException(
                        "AOT cache training run failed with exit code " + exitCode + ". See the output above for details.");
            }
        }
        catch (IOException ex) {
            throw new MojoExecutionException("Unable to prepare the AOT cache training run", ex);
        }
    }

    private List<String> buildCommand(Path cacheFile, Path workDirectory) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        if (this.outOfProcess) {
            command.add("-cp");
            command.add(jarOnlyClasspath(workDirectory));
            command.addAll(outOfProcessArguments(cacheFile));
        }
        else {
            command.add(AotCache.recordingArgument(cacheFile));
            command.add("-cp");
            command.add(jarOnlyClasspath(workDirectory));
            command.add("io.github.vpelikh.aot.trainer.TrainingLauncher");
        }
        for (String packageName : this.packagesToScan) {
            command.add("--select-package=" + packageName);
        }
        if (!this.failOnTestFailure) {
            command.add("--no-fail-on-test-failure");
        }
        if (this.allowEmptyWorkload) {
            command.add("--allow-empty");
        }
        return command;
    }

    /**
     * Build the arguments that launch an out-of-process training run: the launcher class
     * followed by the application, cache, readiness and container options. The application
     * JVM records the cache (see {@code OutOfProcessTrainingLauncher}); the launcher JVM only
     * runs the client tests, so no recording flag is added here.
     * @param cacheFile the AOT cache output path
     * @return the out-of-process launcher arguments
     * @throws IOException if the packaged application JAR cannot be located
     */
    List<String> outOfProcessArguments(Path cacheFile) throws IOException {
        List<String> arguments = new ArrayList<>();
        arguments.add("io.github.vpelikh.aot.trainer.OutOfProcessTrainingLauncher");
        arguments.add("--app-jar=" + resolveApplicationJar());
        arguments.add("--cache=" + cacheFile.toAbsolutePath());
        arguments.add("--layout=" + cacheFile.getParent().toAbsolutePath().resolve("app-layout"));
        arguments.add("--ready-url=" + this.readyUrl);
        if (this.startClass != null && !this.startClass.isBlank()) {
            arguments.add("--start-class=" + this.startClass);
        }
        if (this.trainingJvm != null && !this.trainingJvm.isBlank()) {
            arguments.add("--java=" + this.trainingJvm);
        }
        if (this.containerImage != null && !this.containerImage.isBlank()) {
            arguments.add("--image=" + this.containerImage);
        }
        arguments.add("--container-runtime=" + this.containerRuntime);
        arguments.add("--start-timeout=" + this.startTimeout);
        for (String applicationArgument : this.applicationArguments) {
            arguments.add("--application-arg=" + applicationArgument);
        }
        return arguments;
    }

    /**
     * Resolve the packaged application JAR for out-of-process training. When
     * <code>applicationJar</code> is unset, the repackaged Spring Boot JAR in the build
     * directory is located automatically (the largest relocated <code>.jar</code> whose
     * manifest declares a <code>Start-Class</code>).
     * @return the application JAR
     * @throws IOException if no application JAR can be found
     */
    private Path resolveApplicationJar() throws IOException {
        if (this.applicationJar != null && !this.applicationJar.isBlank()) {
            Path jar = Path.of(this.applicationJar);
            if (!Files.isRegularFile(jar)) {
                throw new IOException("The configured application JAR does not exist: " + jar.toAbsolutePath());
            }
            return jar.toAbsolutePath();
        }
        Path buildDirectory = this.project.getBasedir()
            .toPath()
            .resolve(this.project.getBuild().getDirectory());
        Path located = null;
        try (java.util.stream.Stream<Path> stream = Files.walk(buildDirectory)) {
            for (Path candidate : stream.filter(Files::isRegularFile)
                .filter((path) -> path.getFileName().toString().endsWith(".jar"))
                .filter(this::hasPlainStartClass)
                .toList()) {
                if (located == null || candidate.toFile().length() > located.toFile().length()) {
                    located = candidate;
                }
            }
        }
        if (located == null) {
            throw new IOException("Out-of-process training needs the packaged application JAR, but none with a "
                    + "Start-Class manifest attribute was found under " + buildDirectory
                    + ". Run 'package' before recording, or set the applicationJar parameter.");
        }
        return located.toAbsolutePath();
    }

    /**
     * Whether the given JAR declares a plain Spring Boot <code>Start-Class</code> (that is,
     * not the loader's {@code Main-Class}).
     */
    private boolean hasPlainStartClass(Path jar) {
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar.toFile())) {
            java.util.jar.Manifest manifest = jarFile.getManifest();
            String startClass = (manifest != null) ? manifest.getMainAttributes().getValue("Start-Class") : null;
            return startClass != null && !startClass.isBlank();
        }
        catch (IOException ex) {
            return false;
        }
    }

    /**
     * Build a class path with no non-empty directory, as required by the JVM for AOT cache
     * recording. Directory entries (for example <code>target/test-classes</code>) are packaged
     * into JARs; JAR entries are kept as-is.
     */
    String jarOnlyClasspath(Path workDirectory) throws IOException {
        List<Path> elements = new ArrayList<>();
        try {
            for (String element : this.project.getTestClasspathElements()) {
                elements.add(Path.of(element));
            }
        }
        catch (org.apache.maven.artifact.DependencyResolutionRequiredException ex) {
            throw new IOException("Unable to resolve the test class path", ex);
        }
        // The launcher and its core helpers come from the plugin's own class path; they carry
        // no JUnit. The JUnit Platform generation comes from the project, so a project on a
        // newer generation (for example JUnit 6) is never mixed with ours.
        elements.addAll(pluginClasspath());
        // junit-jupiter does not bring the launcher, so add it unless the project already
        // provides one, always at the project's own JUnit Platform version.
        if (!hasJar(elements, JUnitPlatformVersion.LAUNCHER_FILE_PREFIX)) {
            elements.add(resolveLauncher());
        }
        // Mocking libraries self-attach agents that break cache assembly.
        elements = new ArrayList<>(TrainingClasspath.withoutMockingLibraries(elements));
        return TrainingClasspath.jarOnlyClasspath(elements, workDirectory);
    }

    /**
     * Collect the launcher and its core helpers from the plugin's own class path. These
     * deliberately do not include JUnit; the JUnit Platform comes from the project.
     * @return the plugin class path entries to append
     */
    private List<Path> pluginClasspath() {
        List<Path> elements = new ArrayList<>();
        addCodeSource(elements, io.github.vpelikh.aot.trainer.TrainingLauncher.class);
        addCodeSource(elements, AotCache.class);
        return elements;
    }

    /**
     * Resolve the JUnit Platform launcher at the project's own JUnit Platform version, so the
     * launcher API and the test engine belong to the same generation. Falls back to the
     * launcher version this plugin was built against when the project brings no JUnit Platform.
     */
    private Path resolveLauncher() throws IOException {
        String version = JUnitPlatformVersion.fromCoordinates(dependencyCoordinates())
            .or(() -> JUnitPlatformVersion.find(projectClasspathElements()))
            .orElse(JUnitPlatformVersion.DEFAULT_PLATFORM_VERSION);
        try {
            org.eclipse.aether.artifact.Artifact artifact = new org.eclipse.aether.artifact.DefaultArtifact(
                    "org.junit.platform", "junit-platform-launcher", "jar", version);
            org.eclipse.aether.resolution.ArtifactRequest request = new org.eclipse.aether.resolution.ArtifactRequest(
                    artifact, this.remoteRepositories, null);
            org.eclipse.aether.resolution.ArtifactResult result = this.repositorySystem
                .resolveArtifact(this.repositorySystemSession, request);
            return result.getArtifact().getFile().toPath();
        }
        catch (org.eclipse.aether.resolution.ArtifactResolutionException ex) {
            throw new IOException("Unable to resolve org.junit.platform:junit-platform-launcher:" + version
                    + ". The AOT cache training run needs the JUnit Platform launcher on the training class path.", ex);
        }
    }

    /**
     * Return the resolved <code>group:artifact</code> to version map for the project, so the
     * JUnit Platform version can be read from dependency metadata rather than file names.
     * @return the coordinate map
     */
    private Map<String, String> dependencyCoordinates() {
        Map<String, String> coordinates = new LinkedHashMap<>();
        for (org.apache.maven.artifact.Artifact artifact : this.project.getArtifacts()) {
            coordinates.putIfAbsent(artifact.getGroupId() + ":" + artifact.getArtifactId(), artifact.getVersion());
        }
        return coordinates;
    }

    private List<Path> projectClasspathElements() {
        List<Path> elements = new ArrayList<>();
        try {
            for (String element : this.project.getTestClasspathElements()) {
                elements.add(Path.of(element));
            }
        }
        catch (org.apache.maven.artifact.DependencyResolutionRequiredException ex) {
            // Fall through: treat as an empty class path, so the default launcher version is used.
        }
        return elements;
    }

    private boolean hasJar(List<Path> elements, String namePrefix) {
        return elements.stream()
            .map((element) -> element.getFileName().toString())
            .anyMatch((name) -> name.startsWith(namePrefix));
    }

    private void addCodeSource(List<Path> target, Class<?> type) {
        try {
            java.security.CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                target.add(Path.of(codeSource.getLocation().toURI()));
            }
        }
        catch (Exception ex) {
            throw new IllegalStateException("Unable to locate the code source for " + type.getName(), ex);
        }
    }

    /**
     * Verify the training JVM is new enough before starting the (slow) training run.
     * @throws MojoExecutionException if the training JVM is older than the recording minimum
     */
    private void checkTrainingJdk() throws MojoExecutionException {
        try {
            Process process = new ProcessBuilder(javaExecutable(), "-version").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("version \"(\\d+)").matcher(output);
            if (matcher.find()) {
                int feature = Integer.parseInt(matcher.group(1));
                if (feature < AotCache.MINIMUM_RECORDING_JDK) {
                    throw new MojoExecutionException("AOT cache recording requires JDK " + AotCache.MINIMUM_RECORDING_JDK
                            + " or later, but the training JVM is JDK " + feature
                            + ". Set the plugin's <trainingJvm> or run Maven on JDK " + AotCache.MINIMUM_RECORDING_JDK + "+.");
                }
            }
        }
        catch (IOException ex) {
            throw new MojoExecutionException("Unable to run the training JVM (" + javaExecutable() + ")", ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while checking the training JVM", ex);
        }
    }

    private String javaExecutable() {
        if (this.trainingJvm != null && !this.trainingJvm.isBlank()) {
            return this.trainingJvm;
        }
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private int run(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).inheritIO().start();
        try {
            return process.waitFor();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the AOT cache training run", ex);
        }
    }

    Path resolveCacheFile() {
        return this.project.getBasedir()
            .toPath()
            .resolve(this.project.getBuild().getDirectory())
            .resolve(this.cacheDirectory)
            .resolve(AotCache.CACHE_FILE_NAME)
            .toAbsolutePath()
            .normalize();
    }

    void setProject(MavenProject project) {
        this.project = project;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    void setSkip(boolean skip) {
        this.skip = skip;
    }

    void setCacheDirectory(String cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    void setPackagesToScan(List<String> packagesToScan) {
        this.packagesToScan = packagesToScan;
    }

    void setFailOnTestFailure(boolean failOnTestFailure) {
        this.failOnTestFailure = failOnTestFailure;
    }

    void setAllowEmptyWorkload(boolean allowEmptyWorkload) {
        this.allowEmptyWorkload = allowEmptyWorkload;
    }

    void setTrainingJvm(String trainingJvm) {
        this.trainingJvm = trainingJvm;
    }

    void setOutOfProcess(boolean outOfProcess) {
        this.outOfProcess = outOfProcess;
    }

    void setApplicationJar(String applicationJar) {
        this.applicationJar = applicationJar;
    }

    void setReadyUrl(String readyUrl) {
        this.readyUrl = readyUrl;
    }

    void setStartClass(String startClass) {
        this.startClass = startClass;
    }

    void setContainerImage(String containerImage) {
        this.containerImage = containerImage;
    }

    void setContainerRuntime(String containerRuntime) {
        this.containerRuntime = containerRuntime;
    }

    void setApplicationArguments(List<String> applicationArguments) {
        this.applicationArguments = applicationArguments;
    }

    void setStartTimeout(int startTimeout) {
        this.startTimeout = startTimeout;
    }

}
