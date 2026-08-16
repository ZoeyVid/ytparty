# syntax=docker/dockerfile:1.26.0@sha256:ecfaec9ed6d810b56388c508f4121597bfbba70d41a6dfeee4d8cad5f295fc32
FROM --platform=$BUILDPLATFORM golang:1.26.6-alpine3.24@sha256:3889b425f035be855a72fb4755265311293b6d414521f0a519d819df32222d83 AS build
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
