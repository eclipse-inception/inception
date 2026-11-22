# INCEpTION Docker

This module builds the INCEpTION Docker image. It is excluded from a normal
reactor build and only included when the `dist-docker` profile is active. If you
build only this module, make sure that you have previously built the entire
project so that the latest `inception-app-webapp` standalone artifact is
available in your local Maven repository.

Two profiles control the build:

- `dist-docker` — adds the docker module to the reactor (declared in the parent
  pom). Without this profile, the docker module is not built at all.
- `release-docker` — switches to a multi-platform (`linux/amd64` + `linux/arm64`)
  buildx build and pushes the resulting manifest list to the registry.

Image name and tags are controlled by the `docker.image.name` property
(defaults to `ghcr.io/eclipse-inception/inception-snapshots`).

## SNAPSHOT builds

Build a single-architecture image into your local Docker daemon (no push):

    mvn -Pdist-docker clean install

Build and push a multi-architecture SNAPSHOT image to
`ghcr.io/eclipse-inception/inception-snapshots`:

    mvn -Pdist-docker,release-docker clean deploy

Run the latest published SNAPSHOT:

    docker run -p8080:8080 -v inception-data:/export -it ghcr.io/eclipse-inception/inception-snapshots

Run the latest SNAPSHOT via Docker Compose:

    export INCEPTION_IMAGE=ghcr.io/eclipse-inception/inception-snapshots
    export INCEPTION_VERSION=latest
    docker-compose -f ../inception-doc/src/main/resources/META-INF/asciidoc/admin-guide/scripts/docker-compose.yml -p inception up

## Release builds

Build and push a multi-architecture release image to
`ghcr.io/eclipse-inception/inception`:

    mvn -Pdist-docker,release-docker clean deploy -Ddocker.image.name=ghcr.io/eclipse-inception/inception

Run the released image:

    docker run -p8080:8080 -v inception-data:/export -it ghcr.io/eclipse-inception/inception

## Building only the docker module

When invoking Maven from inside the `inception-dist-docker/` directory, the
`dist-docker` profile is not needed (it only gates module inclusion in the
parent reactor). The `release-docker` profile is still required to enable
multi-arch + push:

    cd inception-dist-docker
    mvn install                         # local single-arch build
    mvn -Prelease-docker deploy         # multi-arch build + push

## Authentication

Pushing to GitHub Container Registry requires authentication. Log in via Docker
Desktop or on the command line:

    docker login ghcr.io

## Options

If you want to keep the application data easily accessible in a folder on your
host (e.g. if you want to use a custom `settings.properties` file), provide a
path on the host to the `-v` parameter:

    docker run -v /path/on/host/inception/repository:/export ...

## More on Docker Compose

To use **docker-compose**, specify:

    export INCEPTION_HOME=/path/on/host/inception
    export INCEPTION_PORT=port-on-host

In the folder where
`inception-doc/src/main/resources/META-INF/asciidoc/admin-guide/scripts/docker-compose.yml`
is located, call:

    docker-compose -p inception up -d

This starts an INCEpTION instance together with a MariaDB database.

To stop, call:

    docker-compose -p inception down
