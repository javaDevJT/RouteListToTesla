# syntax=docker/dockerfile:1
ARG JAVA_BUILD_IMAGE=eclipse-temurin:25-jdk-noble@sha256:5b14970485a676b41faa08f4a7bc8716cc20915daa1d581d7a75f37a8ebaf9a8
ARG RUNTIME_IMAGE=ubuntu:26.04@sha256:da6fc2be547864451aa253836dd926da33623312df4a9a243e35dc877c378a78

FROM ${JAVA_BUILD_IMAGE} AS java-build
WORKDIR /build
COPY --chmod=0755 mvnw ./
COPY .mvn/ .mvn/
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp package

# jdeps covers static references; Spring/TLS/locale providers also use reflection.
RUN mkdir /build/extracted \
    && cd /build/extracted \
    && jar -xf /build/target/RouteListToTesla-*.jar \
    && jdeps --ignore-missing-deps --recursive --multi-release 25 \
        --print-module-deps --class-path 'BOOT-INF/lib/*' BOOT-INF/classes > /build/java-modules.txt \
    && jlink --add-modules "$(cat /build/java-modules.txt),jdk.crypto.ec,jdk.unsupported,jdk.charsets,jdk.localedata" \
        --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output /build/jre

FROM ${RUNTIME_IMAGE} AS runtime-base
RUN apt-get update && apt-get upgrade -y \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl findutils python3 tesseract-ocr tesseract-ocr-eng \
        libgomp1 libgl1 libglib2.0-0t64 libxcb1 libfreetype6 libfontconfig1 \
    && rm -rf /var/lib/apt/lists/*

# Keep native compilation independent of the runtime security-refresh stage.
FROM ${RUNTIME_IMAGE} AS opencv-build
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential python3-dev python3-venv \
    && rm -rf /var/lib/apt/lists/*
RUN python3 -m venv /opt/opencv-build \
    && CMAKE_ARGS="-DWITH_FFMPEG=OFF -DVIDEOIO_ENABLE_PLUGINS=OFF -DWITH_GSTREAMER=OFF -DWITH_V4L=OFF -DWITH_QT=OFF -DWITH_GTK=OFF -DWITH_OPENGL=OFF -DBUILD_EXAMPLES=OFF" \
         CMAKE_BUILD_PARALLEL_LEVEL=4 \
       /opt/opencv-build/bin/pip wheel --no-cache-dir --no-deps \
         --no-binary=opencv-python,opencv-python-headless \
         --wheel-dir=/opencv-wheelhouse \
         opencv-python==5.0.0.93 opencv-python-headless==5.0.0.93

FROM runtime-base AS ocr-build
RUN apt-get update && apt-get install -y --no-install-recommends python3-venv \
    && rm -rf /var/lib/apt/lists/*
COPY --from=opencv-build /opencv-wheelhouse /tmp/opencv-wheelhouse
COPY ocr/requirements.txt /app/ocr/requirements.txt
RUN --mount=type=cache,target=/root/.cache/pip python3 -m venv /opt/ocr \
    && /opt/ocr/bin/pip install --no-cache-dir --no-index --no-deps \
         --find-links=/tmp/opencv-wheelhouse \
         opencv-python==5.0.0.93 opencv-python-headless==5.0.0.93 \
    && rm -rf /tmp/opencv-wheelhouse \
    && /opt/ocr/bin/pip install -r /app/ocr/requirements.txt \
    && /opt/ocr/bin/pip check
COPY ocr/verify_opencv.py /app/ocr/verify_opencv.py
RUN /opt/ocr/bin/python /app/ocr/verify_opencv.py
COPY ocr/download_models.py ocr/models.json /app/ocr/
RUN /opt/ocr/bin/python /app/ocr/download_models.py --directory /app/ocr/models --manifest /app/ocr/models.json

FROM runtime-base AS runtime
RUN groupadd --gid 10001 router \
    && useradd --uid 10001 --gid router --no-create-home --shell /usr/sbin/nologin router \
    && mkdir -p /app/cache && chown 10001:10001 /app/cache
WORKDIR /app
COPY --from=java-build /build/jre /opt/java
COPY --from=java-build /build/java-modules.txt /app/java-modules.txt
COPY --from=java-build /build/target/*.jar /app/app.jar
COPY --from=ocr-build /opt/ocr /opt/ocr
COPY --from=ocr-build /app/ocr /app/ocr
COPY ocr/consensus.py /app/ocr/consensus.py
COPY --chmod=0755 ocr/run /app/ocr/run

# Validate installed packages in the assembled runtime, after all stage copies.
RUN set -eu; \
    for dependency in libssl3t64 openssl openssl-provider-legacy; do \
        installed_version="$(dpkg-query -W -f='${Version}' "$dependency")"; \
        printf '%s %s\n' "$dependency" "$installed_version"; \
        dpkg --compare-versions "$installed_version" ge 3.5.5-1ubuntu3.6; \
    done

ENV JAVA_HOME=/opt/java PATH="/opt/java/bin:${PATH}" \
    PYTHONDONTWRITEBYTECODE=1 \
    OMP_THREAD_LIMIT=2 OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=2 MKL_NUM_THREADS=2 \
    OCR_TESSERACT_EXECUTABLE=/app/ocr/run OCR_MODEL_MANIFEST=/app/ocr/models.json
ARG VCS_REF=unknown
LABEL org.opencontainers.image.source="https://github.com/javaDevJT/RouteListToTesla" \
    org.opencontainers.image.revision="${VCS_REF}"

USER 10001:10001
EXPOSE 10088
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:10088/login >/dev/null || exit 1
CMD ["java", "-jar", "/app/app.jar"]
