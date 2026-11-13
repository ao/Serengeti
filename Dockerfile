# syntax=docker/dockerfile:1
# The jar does not depend on the CPU, so it is built once on the build machine's platform and copied into
# a JRE image for each target platform (linux/amd64, linux/arm64).
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline
COPY src src
# Release builds pass the version from the tag (release.yml); other builds keep the pom's version.
ARG VERSION=
RUN if [ -n "$VERSION" ]; then \
      mvn -B -ntp -q org.codehaus.mojo:versions-maven-plugin:2.18.0:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false; \
    fi \
 && mvn -B -ntp -q -DskipTests package \
 && cp target/Serengeti-*-jar-with-dependencies.jar /serengeti.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 serengeti \
 && useradd --system --uid 10001 --gid 10001 --home-dir /data --no-create-home serengeti \
 && mkdir /data && chown 10001:10001 /data && chmod 700 /data
COPY --from=build /serengeti.jar /opt/serengeti/serengeti.jar
USER 10001:10001
# All interfaces inside the container's own network namespace. Nothing is published unless the operator
# passes -p; publish to 127.0.0.1 unless other machines need to reach the node.
ENV SERENGETI_BIND=0.0.0.0 \
    SERENGETI_DATA_DIR=/data \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
VOLUME /data
EXPOSE 1985
HEALTHCHECK --interval=30s --timeout=10s --start-period=20s --retries=3 \
  CMD ["java", "-XX:TieredStopAtLevel=1", "-Xshare:auto", "-jar", "/opt/serengeti/serengeti.jar", "--health"]
ENTRYPOINT ["java", "-jar", "/opt/serengeti/serengeti.jar"]
