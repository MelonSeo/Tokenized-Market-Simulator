# UNPAID test tools only. The build context is this directory, never repository/runtime.
FROM node:22.22.0-bookworm-slim@sha256:7cc56ef285a8568121537d17b05e72128f01b89c54607b51acf084a50ef483f3 AS node
FROM eclipse-temurin:21.0.10_7-jdk-jammy@sha256:25d1276565738d3c805e632a4542c3a7598866ef967f4def6544c15de3a74b14 AS jdk
FROM python:3.12.12-slim-bookworm@sha256:2986c55feb36e6cae00fa1fefb454283e4b33f35e75ff8bdd123b134130be301
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
RUN apt-get update && apt-get install -y --no-install-recommends chromium chromium-sandbox fonts-noto-cjk ca-certificates \
    && dpkg-query -W > /opt/pilot-runtime-packages.txt \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 1000 --create-home pilot
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH CHROME_PATH=/usr/bin/chromium HOME=/home/pilot
USER 1000:1000
WORKDIR /work/backend
ENTRYPOINT ["python", "-B", "-S", "/work/deployment/ai_pilot_linux_inner.py"]
