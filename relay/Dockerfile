# syntax=docker/dockerfile:1.25.0@sha256:0adf442eae370b6087e08edc7c50b552d80ddf261576f4ebd6421006b2461f12
FROM --platform=$BUILDPLATFORM golang:1.26.5-alpine3.24@sha256:99e12cfb19b753915f9b9fdc5a99f1869a24a69d3a0955832d5702e7fa68f1be AS build
COPY . /src
WORKDIR /src

ARG TARGETARCH
ARG TARGETOS
ARG CGO_ENABLED=0
RUN GOOS="$TARGETOS" GOARCH="$TARGETARCH" go build -tags timetzdata -buildmode=pie -trimpath -ldflags="-s -w -buildid=" -o relay .

FROM alpine:3.24.1@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b
RUN apk upgrade --no-cache -a && \
    apk add --no-cache tini
USER nobody:nobody
COPY --from=build /src/relay /usr/local/bin/relay
ENTRYPOINT ["tini", "--", "relay"]
