# syntax=docker/dockerfile:1.28.0@sha256:bb22d9815c728170f72750f4e5b0d672e06176142e1d602c7e66c050100b7e5b
FROM --platform=$BUILDPLATFORM golang:1.27.3-alpine3.24@sha256:d200d2dd76b03a99b52651fd1325aff7dcd35422c35c01bac56571b0507f3deb AS build
COPY . /src
WORKDIR /src

ARG TARGETARCH
ARG TARGETOS
ARG CGO_ENABLED=0
RUN GOOS="$TARGETOS" GOARCH="$TARGETARCH" go build -tags timetzdata -buildmode=pie -trimpath -ldflags="-s -w -buildid=" -o relay .

FROM alpine:3.24.3@sha256:be2656061b536c1883c4034790e21d0a09c5aaaf51968b89368a3c0371ea2c9a
RUN apk upgrade --no-cache -a && \
    apk add --no-cache tini
USER 65534:65534
COPY --from=build /src/relay /usr/local/bin/relay
ENTRYPOINT ["tini", "--", "relay"]
