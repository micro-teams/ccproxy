# A thin layer over the stock JRE image, adding the CLI tools the backend shells out to. Today that
# is an ssh client (the backend SSHes into freshly-provisioned machines to run init-machine.py; the
# stock image has none) plus curl for the container healthcheck; more may be added later. Built and
# pushed to ghcr.io/micro-teams/eclipse-temurin by CI; the deploy compose is pinned to its digest at
# package time.
FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends openssh-client curl \
    && rm -rf /var/lib/apt/lists/*
