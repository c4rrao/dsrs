# Docker Hub has no 2.8.12 image. Put canton-open-source-2.8.12.tar.gz from
# https://github.com/digital-asset/canton/releases/tag/v2.8.12 next to this file and run
#   docker build -t dsrs-canton-2.8.12:local .
FROM eclipse-temurin:17-jre-jammy

WORKDIR /canton
COPY canton-open-source-2.8.12.tar.gz /tmp/canton.tar.gz
RUN tar xzf /tmp/canton.tar.gz --strip-components=1 -C /canton \
    && rm /tmp/canton.tar.gz

ENTRYPOINT ["bin/canton"]
