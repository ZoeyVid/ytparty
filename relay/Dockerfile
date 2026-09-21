# syntax=docker/dockerfile:1.27.0@sha256:bde3983e9c939224420ddaf6b784cc30e09b035a4dea01f581230c50809f372e
FROM --platform=$BUILDPLATFORM golang:1.27.1-alpine3.24@sha256:4cb7ac979db5fcc41cae44b2227ba5ab8a51e8807f40d9ba4dee20a0ad960b5b AS build
COPY . /src
WORKDIR /src

ARG TARGETARCH
ARG TARGETOS
ARG CGO_ENABLED=0
RUN GOOS="$TARGETOS" GOARCH="$TARGETARCH" go build -tags timetzdata -buildmode=pie -trimpath -ldflags="-s -w -buildid=" -o relay .

FROM alpine:3.24.1@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b
RUN apk upgrade --no-cache -a && \
    apk add --no-cache tini
USER 65534:65534
COPY --from=build /src/relay /usr/local/bin/relay
ENTRYPOINT ["tini", "--", "relay"]
