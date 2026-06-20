FROM --platform=$BUILDPLATFORM golang:1.26-alpine@sha256:3ad57304ad93bbec8548a0437ad9e06a455660655d9af011d58b993f6f615648 AS build
ARG TARGETOS TARGETARCH
WORKDIR /src
COPY go.mod crypto.go relay.go ./
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath -ldflags "-s -w" -o /relay .

FROM scratch
COPY --from=build /relay /relay
ENV YTPARTY_RELAY_PORT=25599
EXPOSE 25599
ENTRYPOINT ["/relay"]
