# jfrom sample-app image.
#
# BUILD CONTEXT: this Dockerfile must be built from the REPO ROOT, not from
# sample-app/, because it needs the root aggregator pom.xml (for the Maven
# module/version metadata) and, optionally, the sibling agent/target jar
# produced by the agent module.
#
#   docker build -f sample-app/Dockerfile -t jfrom-sample-app .
#
# A JRE-only runtime image will NOT work here: JFR and the jfrom agent
# both need the full JDK (jcmd/JFR support, Instrumentation), so both stages
# use a full JDK, not a JRE.
#
# The agent jar (agent/target/jfrom-agent.jar, built by the separate
# `agent` module) is optional at build time: if it isn't there yet - e.g.
# because the agent module hasn't been built, or its premain class doesn't
# exist yet - the image still builds, and the container still starts the
# app without -javaagent (see docker/entrypoint.sh). Once a real agent jar
# exists at that path when the image is built, it is baked in automatically.

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-25
ARG RUNTIME_IMAGE=eclipse-temurin:25-jdk

FROM ${MAVEN_IMAGE} AS build
WORKDIR /workspace

# Only the poms needed to resolve the sample-app module + parent BOM/version
# management. agent/src is intentionally NOT copied: this stage never builds
# the agent module, it only (optionally) picks up its already-built jar below.
COPY pom.xml pom.xml
COPY agent/pom.xml agent/pom.xml
COPY sample-app/pom.xml sample-app/pom.xml
COPY sample-app/src sample-app/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -B -pl sample-app package -DskipTests

# Stage the (possibly absent) agent jar without ever failing the build:
# `--mount=type=bind` gives this RUN step read-only access to the *entire*
# build context (repo root) just for this step, without copying it into an
# image layer, so we can shell-test for the sibling jar's existence.
RUN --mount=type=bind,source=.,target=/build-context,ro \
    mkdir -p /workspace/agent-lib && \
    if [ -f /build-context/agent/target/jfrom-agent.jar ]; then \
      cp /build-context/agent/target/jfrom-agent.jar /workspace/agent-lib/jfrom-agent.jar; \
      echo "Bundled agent/target/jfrom-agent.jar into the image"; \
    else \
      echo "No agent/target/jfrom-agent.jar found at build time - image will run without a bundled agent"; \
    fi

FROM ${RUNTIME_IMAGE} AS runtime
WORKDIR /app

# Configurable agent path/options - override with `docker build --build-arg`
# or `docker run -e` / compose `environment:`.
ARG AGENT_OPTS=port=9404
ARG AGENT_JAR=/app/agent/jfrom-agent.jar
ENV AGENT_OPTS=${AGENT_OPTS} \
    AGENT_JAR=${AGENT_JAR} \
    APP_JAR=/app/sample-app.jar \
    JAVA_OPTS="-Xmx512m"

COPY --from=build /workspace/sample-app/target/sample-app.jar /app/sample-app.jar
COPY --from=build /workspace/agent-lib/ /app/agent/
COPY sample-app/docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8080 9404

ENTRYPOINT ["/app/entrypoint.sh"]
