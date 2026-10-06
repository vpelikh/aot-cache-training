# A Dockerfile-based alternative to the buildpack examples: it ships the recorded AOT cache
# in a plain `docker build`, with no Paketo buildpack involved.
#
# Prerequisites (run in this directory first):
#   ./gradlew aotCacheTraining verifyAotCache   # records build/aot-cache/application.aot
#
# Build and run:
#   docker build -t dockerfile-spring-boot-example .
#   docker run --rm -p 8080:8080 dockerfile-spring-boot-example
#
# The base image must be the same JVM the cache was recorded on (aotCacheTraining above uses
# containerImage = "eclipse-temurin:25" for exactly this reason).
FROM eclipse-temurin:25

RUN adduser --system --home /app --disabled-password --disabled-login app-user

WORKDIR /app

# The boot JAR (Spring Boot also produces a non-runnable -plain.jar, excluded below).
COPY build/libs/ /app/libs/
# The AOT cache recorded from that JAR's own JVM.
COPY build/aot-cache/application.aot /app/aot-cache/application.aot

# Flatten the boot JAR to the class path (runner.jar + lib/) the cache was recorded against.
# An AOT cache only loads when the class path and layout match the recording, so the JVM is
# started with `-cp runner.jar` (its manifest Class-Path supplies lib/), exactly as the
# trainer did. The extracted file timestamps are normalized to the value the Paketo buildpack
# uses, otherwise the JVM rejects the cache with "timestamp has changed".
RUN set -eux; \
    boot_jar="$(find /app/libs -name '*.jar' ! -name '*-plain.jar' | head -n1)"; \
    java -Djarmode=tools -jar "$boot_jar" extract --destination /app/extracted; \
    mv /app/extracted/*.jar /app/runner.jar; \
    mv /app/extracted/lib /app/lib; \
    rmdir /app/extracted; \
    rm -rf /app/libs; \
    find /app -name '*.jar' -exec touch -d @315532801 {} +; \
    touch -d @315532801 /app/aot-cache/application.aot; \
    chown -R app-user /app

USER app-user

# Load the recorded cache at startup. It is used only when the JVM build, architecture, class
# path and flags match the recording; otherwise the JVM logs a warning and starts without it.
ENTRYPOINT ["java", "-XX:AOTCache=/app/aot-cache/application.aot", "-cp", "/app/runner.jar", "example.ExampleApplication"]