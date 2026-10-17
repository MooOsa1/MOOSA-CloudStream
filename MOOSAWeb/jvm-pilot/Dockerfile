# Separate experimental Render Free container. Does not replace MOOSA Web.
FROM eclipse-temurin:17-jdk-jammy AS builder
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
  curl git python3 unzip ca-certificates && rm -rf /var/lib/apt/lists/*
COPY MOOSAWeb/jvm-pilot/JvmOriginalProviderLinks.java /build/JvmOriginalProviderLinks.java
COPY MOOSAWeb/headless-jvm-shims /build/shims
COPY MOOSAWeb/jvm-pilot/copy-runtime.init.gradle /build/runtime.init.gradle
RUN set -eux; \
  git clone --depth 1 https://github.com/recloudstream/cloudstream.git /tmp/cloudstream-official; \
  cd /tmp/cloudstream-official; chmod +x gradlew; \
  ./gradlew :library:jvmJar --no-daemon --max-workers=2 --console=plain; \
  mkdir -p /tmp/moosa-jvm/deps; \
  cp "$(find library/build/libs -maxdepth 1 -name '*jvm*.jar' -print -quit)" \
    /tmp/moosa-jvm/official-cloudstream-jvm.jar
RUN set -eux; \
  cd /tmp/moosa-jvm; \
  curl --fail --location --silent --show-error \
    https://raw.githubusercontent.com/SaurabhKaperwan/CSX/builds/CineStream.cs3 \
    -o original-cinestream.cs3; \
  unzip -o -q original-cinestream.cs3 classes.dex; \
  curl --fail --location --silent --show-error \
    https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip \
    -o dex-tools.zip; \
  unzip -o -q dex-tools.zip -d converter; \
  chmod +x converter/dex-tools-v2.4/d2j-dex2jar.sh; \
  converter/dex-tools-v2.4/d2j-dex2jar.sh --force \
    --output original-cinestream.jar classes.dex
RUN set -eux; \
  cd /tmp/cloudstream-official; \
  ./gradlew --init-script /build/runtime.init.gradle \
    :library:copyMoosaRuntime --no-daemon --max-workers=2 \
    --console=plain --no-configuration-cache; \
  mkdir -p /tmp/moosa-jvm/shim-classes; \
  javac -cp '/tmp/moosa-jvm/deps/*' -d /tmp/moosa-jvm/shim-classes \
    $(find /build/shims -name '*.java'); \
  jar cf /tmp/moosa-jvm/deps/moosa-jvm-shims.jar \
    -C /tmp/moosa-jvm/shim-classes .; \
  javac -d /tmp/moosa-jvm /build/JvmOriginalProviderLinks.java; \
  rm -rf /tmp/moosa-jvm/converter /tmp/moosa-jvm/classes.dex \
    /tmp/moosa-jvm/dex-tools.zip /tmp/moosa-jvm/original-cinestream.cs3

FROM node:22-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
  openjdk-17-jre-headless && rm -rf /var/lib/apt/lists/*
COPY --from=builder /tmp/moosa-jvm /opt/moosa/jvm
COPY MOOSAWeb/jvm-pilot/index.mjs /opt/moosa/index.mjs
RUN chmod -R a+rX /opt/moosa/jvm
ENV PORT=10000 NODE_ENV=production NODE_OPTIONS=--max-old-space-size=64
WORKDIR /opt/moosa
EXPOSE 10000
CMD ["node","index.mjs"]
