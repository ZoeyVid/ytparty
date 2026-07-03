FROM --platform=$BUILDPLATFORM golang:1.26.4-alpine3.24@sha256:3ad57304ad93bbec8548a0437ad9e06a455660655d9af011d58b993f6f615648 AS build
COPY . /src
WORKDIR /src

ARG TARGETARCH
ARG TARGETOS
ARG CGO_ENABLED=0
RUN GOOS="$TARGETOS" GOARCH="$TARGETARCH" go build -buildmode=pie -trimpath -ldflags="-s -w -buildid=" -o relay .

FROM alpine:3.24.1@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b
RUN apk add --no-cache tzdata tini
USER nobody:nobody
COPY --from=build /src/relay /usr/local/bin/relay
ENTRYPOINT ["tini", "--", "relay"]
