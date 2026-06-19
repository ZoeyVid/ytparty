FROM --platform=$BUILDPLATFORM golang:1.24-alpine AS build
ARG TARGETOS TARGETARCH
WORKDIR /src
COPY go.mod crypto.go relay.go ./
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath -ldflags "-s -w" -o /relay .

FROM scratch
COPY --from=build /relay /relay
ENV YTPARTY_RELAY_PORT=25599
EXPOSE 25599
ENTRYPOINT ["/relay"]
