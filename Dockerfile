# ---------------------------------------------------------------------------
# Multi-stage build for the Trust Registry
# ---------------------------------------------------------------------------

# --- Stage 1: build --------------------------------------------------------
FROM docker.io/gradle:9.7.1-jdk25 AS build
ARG SKIP_TESTS=false
WORKDIR /workspace
COPY build.gradle settings.gradle ./
COPY gradle ./gradle
COPY config ./config
COPY src ./src
RUN if [ "$SKIP_TESTS" = "true" ]; then \
      gradle build --no-daemon -x test -x checkstyleMain -x checkstyleTest; \
    else \
      gradle build --no-daemon; \
    fi

# --- Stage 2: runtime ------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S nonroot && adduser -S nonroot -G nonroot

# The synchronised Trusted Lists live here so a restart without connectivity
# still boots with the last known good anchor set.
RUN mkdir -p /var/cache/trust-registry && chown -R nonroot:nonroot /var/cache/trust-registry
VOLUME /var/cache/trust-registry

USER nonroot
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/trust-registry.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "/app/trust-registry.jar"]
