FROM --platform=$BUILDPLATFORM golang:1.26.4-alpine3.24@sha256:3ad57304ad93bbec8548a0437ad9e06a455660655d9af011d58b993f6f615648 AS build
ARG TARGETARCH
ARG TARGETOS
WORKDIR /src
COPY . /src
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -buildmode=pie -trimpath -ldflags="-s -w -buildid=" -o relay .

FROM scratch
COPY --from=build /src/relay /usr/local/bin/relay
EXPOSE 25599
ENTRYPOINT ["relay"]
