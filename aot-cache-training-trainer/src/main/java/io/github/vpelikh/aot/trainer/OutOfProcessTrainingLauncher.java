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

package io.github.vpelikh.aot.trainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Runs the integration tests as an external client of the packaged application while the
 * application records a JVM AOT cache in its own JVM.
 *
 * <p>This is the only training mode the plugins use. It exists because an AOT cache only
 * loads against the exact class path it was recorded with, and the class path of the
 * packaged application ({@code runner.jar} plus {@code lib/}) is shorter than a test class
 * path. Recording from the application's own JVM makes the recorded class path <em>be</em>
 * the runtime class path, so the cache is usable by the image. The tests only talk to the
 * application over HTTP, exactly as {@code @QuarkusIntegrationTest} does.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * java -cp <test jars> io.github.vpelikh.aot.trainer.OutOfProcessTrainingLauncher \
 *      --app-jar build/libs/app.jar \
 *      --cache build/aot-cache/application.aot \
 *      --ready-url http://localhost:8080/ \
 *      --start-class com.example.Application \
 *      --image my-app:latest
 * }</pre>
 *
 * <p>Recognized arguments:
 * <ul>
 * <li>{@code --app-jar <path>} the packaged application JAR (required).</li>
 * <li>{@code --cache <path>} the AOT cache output path (required).</li>
 * <li>{@code --ready-url <url>} a URL polled until it returns 2xx/3xx (required).</li>
 * <li>{@code --start-class <fqcn>} the application start class (required).</li>
 * <li>{@code --layout <dir>} the working directory for the extracted application layout
 * (defaults to a sibling of the cache).</li>
 * <li>{@code --java <path>} the local {@code java} executable (defaults to the one running
 * this launcher).</li>
 * <li>{@code --image <image>} record inside this container image, so the cache matches the
 * image's JVM build and architecture.</li>
 * <li>{@code --container-runtime <name>} the container runtime (defaults to {@code docker}).</li>
 * <li>{@code --application-arg <value>} an application argument (repeatable).</li>
 * <li>{@code --start-timeout <seconds>} how long to wait for readiness (defaults to 120).</li>
 * </ul>
 * <p>Arguments that restrict the test selection
 * ({@code --select-package}, {@code --select-class}, {@code --no-fail-on-test-failure},
 * {@code --allow-empty}) are also recognized and applied to the test run.
 *
 * @author Vasily Pelikh
 */
public final class OutOfProcessTrainingLauncher {

    /**
     * System property that exposes the running application's base URL to the training
     * tests, for example {@code http://localhost:8080}. Tests read it with
     * {@code System.getProperty("aot.training.url")} and drive the application over HTTP.
     */
    public static final String TRAINING_URL_PROPERTY = "aot.training.url";

    private OutOfProcessTrainingLauncher() {
    }

    /**
     * Run the out-of-process training workload.
     * @param args command-line arguments, see the class Javadoc
     * @throws IOException if the application cannot be started or stopped
     */
    public static void main(String[] args) throws IOException {
        Options options = Options.parse(List.of(args));
        if (options.startClass == null) {
            options.startClass = resolveStartClass(options.appJar);
        }
        // Bind to a free port so a busy port (for example a leftover application on 8080)
        // cannot make the readiness check pass against the wrong server.
        int port = findFreePort();
        options.applicationArguments.add("--server.port=" + port);
        options.readyUrl = withPort(options.readyUrl, port);
        System.out.println("[aot-cache-training] Starting " + options.appJar + " to record an AOT cache to "
                + options.cacheFile);
        AppProcess process = new AppProcess(options.appJar, options.layoutDirectory, options.cacheFile,
                options.javaExecutable, options.startClass, options.applicationArguments, options.startTimeout,
                options.containerImage, options.containerRuntime, options.jvmArguments);
        int exitCode;
        boolean started = false;
        // Keep the start inside the try: if the application never becomes ready (or extraction
        // or the container fails), the finally must still stop what was started, otherwise the
        // application process (or container) is orphaned when this JVM exits.
        try {
            process.start(options.readyUrl);
            started = true;
            String baseUrl = baseUrl(options.readyUrl);
            System.setProperty(TRAINING_URL_PROPERTY, baseUrl);
            System.out.println("[aot-cache-training] Application is ready at " + baseUrl
                    + "; running the training tests. Tests can read the base URL from the '"
                    + TRAINING_URL_PROPERTY + "' system property.");
            exitCode = TestRun.execute(options.testArguments);
        }
        finally {
            boolean stopped = process.stop();
            if (started && !stopped) {
                System.out.println("[aot-cache-training] Warning: the application did not stop cleanly, so the "
                        + "AOT cache may not have been assembled.");
            }
        }
        System.exit(exitCode);
    }

    private static String baseUrl(URI readyUrl) {
        int port = readyUrl.getPort();
        String authority = (port >= 0) ? readyUrl.getHost() + ":" + port : readyUrl.getHost();
        return readyUrl.getScheme() + "://" + authority;
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static URI withPort(URI readyUrl, int port) {
        return URI.create(readyUrl.getScheme() + "://" + readyUrl.getHost() + ":" + port
                + ((readyUrl.getRawPath() != null) ? readyUrl.getRawPath() : ""));
    }

    private static String resolveStartClass(Path appJar) throws IOException {
        try (JarFile jar = new JarFile(appJar.toFile())) {
            Manifest manifest = jar.getManifest();
            String startClass = (manifest != null) ? manifest.getMainAttributes().getValue("Start-Class") : null;
            if (startClass == null || startClass.isBlank()) {
                throw new IOException("The application JAR " + appJar
                        + " has no Start-Class manifest attribute; pass --start-class explicitly.");
            }
            return startClass;
        }
    }

    private static final class Options {

        private final List<String> testArguments = new ArrayList<>();

        private Path appJar;

        private Path cacheFile;

        private Path layoutDirectory;

        private URI readyUrl;

        private String startClass;

        private String javaExecutable = System.getProperty("java.home") + "/bin/java";

        private String containerImage;

        private String containerRuntime = "docker";

        private final List<String> applicationArguments = new ArrayList<>();

        private final List<String> jvmArguments = new ArrayList<>();

        private Duration startTimeout = Duration.ofSeconds(120);

        private static Options parse(List<String> args) {
            Options options = new Options();
            for (int i = 0; i < args.size(); i++) {
                String argument = args.get(i);
                if (argument.startsWith("--app-jar=")) {
                    options.appJar = Path.of(argument.substring("--app-jar=".length()));
                }
                else if (argument.startsWith("--cache=")) {
                    options.cacheFile = Path.of(argument.substring("--cache=".length()));
                }
                else if (argument.startsWith("--layout=")) {
                    options.layoutDirectory = Path.of(argument.substring("--layout=".length()));
                }
                else if (argument.startsWith("--ready-url=")) {
                    options.readyUrl = URI.create(argument.substring("--ready-url=".length()));
                }
                else if (argument.startsWith("--start-class=")) {
                    options.startClass = argument.substring("--start-class=".length());
                }
                else if (argument.startsWith("--java=")) {
                    options.javaExecutable = argument.substring("--java=".length());
                }
                else if (argument.startsWith("--image=")) {
                    options.containerImage = argument.substring("--image=".length());
                }
                else if (argument.startsWith("--container-runtime=")) {
                    options.containerRuntime = argument.substring("--container-runtime=".length());
                }
                else if (argument.startsWith("--application-arg=")) {
                    options.applicationArguments.add(argument.substring("--application-arg=".length()));
                }
                else if (argument.startsWith("--jvm-arg=")) {
                    options.jvmArguments.add(argument.substring("--jvm-arg=".length()));
                }
                else if (argument.startsWith("--start-timeout=")) {
                    options.startTimeout = Duration
                        .ofSeconds(Long.parseLong(argument.substring("--start-timeout=".length())));
                }
                else if (argument.startsWith("--select-package=") || argument.startsWith("--select-class=")
                        || "--no-fail-on-test-failure".equals(argument) || "--allow-empty".equals(argument)) {
                    options.testArguments.add(argument);
                }
                else if ("--select-package".equals(argument) || "--select-class".equals(argument)) {
                    options.testArguments.add(argument);
                    if (i + 1 < args.size()) {
                        options.testArguments.add(args.get(++i));
                    }
                }
            }
            if (options.appJar == null || options.cacheFile == null || options.readyUrl == null) {
                throw new IllegalArgumentException(
                        "Usage: OutOfProcessTrainingLauncher --app-jar <jar> --cache <file> --ready-url <url> "
                                + "[--start-class <class>] [--layout <dir>] [--java <path>] [--image <image>] "
                                + "[--container-runtime <name>] [--application-arg <value>] [--jvm-arg <value>] "
                                + "[--start-timeout <seconds>]");
            }
            if (options.layoutDirectory == null) {
                Path parent = options.cacheFile.toAbsolutePath().getParent();
                options.layoutDirectory = ((parent != null) ? parent : Path.of(".")).resolve("app-layout");
            }
            return options;
        }

    }

}