FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod main.go ./
RUN CGO_ENABLED=0 go build -o /authorizer .

FROM gcr.io/distroless/static:nonroot
COPY --from=build /authorizer /authorizer
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/authorizer"]
