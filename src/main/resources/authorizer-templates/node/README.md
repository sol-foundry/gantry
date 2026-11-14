# __NAME__: a custom authorizer (Node.js)

A small HTTP server that decides who may call your app through the Gantry gateway. It uses only
Node's built-in `http` module, so there is nothing to install. Change `authorize` in `server.js` to check whatever your app needs.

## The contract

For every request on a route that uses this authorizer, the gateway first sends a `POST` to this
server with the caller's headers:

| Header | What it holds |
| --- | --- |
| `Authorization` | The caller's credentials, as they sent them |
| `X-Forwarded-Method` | The method of the original request (`GET`, `POST`, ...) |
| `X-Forwarded-Uri` | The path and query of the original request |
| `Cookie`, `X-Request-Id`, `X-Forwarded-Host`, `X-User-Timezone` | Passed along when the caller sent them |

The server listens on `$PORT` (8080 unless set) and answers:

- **2xx** to let the request through. Response headers carry the caller's identity, for example
  `x-user-id: dev-user`.
- **Anything else** to refuse it. The status and body go back to the caller as they are; the
  template answers `401` with `{"error":"unauthorized"}`. If the server cannot be reached at all, the
  request is refused too.

As written, the template accepts only `Authorization: Bearer dev-token` and answers
`x-user-id: dev-user`.

## Which headers reach the app

The gateway's identity headers (`x-user-id`, `x-gateway-user-email`, `x-gateway-auth-strategy`,
`x-internal-api-key`, `x-error-code`, or the app's `stripHeaders`) are copied from this server's
response onto the request the app receives, and so is every header the route (or the top-level
`headers` of `gateway.yml`) names. Values given there are ignored for a custom authorizer: the
authorizer's response decides them, and one it leaves out does not reach the app. Callers can never set these
themselves: the gateway removes them from every incoming request.

## Using it

Build it and run it next to the app's gateway (rebuild by pushing again):

```sh
gantry authorizer push <app> __NAME__ <this directory>
```

Then point a route at it in the app's `gateway.yml`:

```yaml
authorizers:
  __NAME__:
    type: custom
    container: __NAME__
    env:
      # Passed to the server as environment variables. Secrets are set with
      # `gantry secret set <app> API_KEY` and never written into gateway.yml.
      API_KEY: "{{secret:API_KEY}}"

routes:
  - name: api
    match:
      path: /api/**
    auth: __NAME__
    headers:
      x-user-id: ""
```

Until every secret its `env` names is set, the gateway does not call the authorizer: its routes answer 503 naming
the missing secret. `PORT` is always set by Gantry.

To try it on its own:

```sh
docker build -t __NAME__ .
docker run --rm -e PORT=8080 -p 8080:8080 __NAME__
curl -i -H 'Authorization: Bearer dev-token' localhost:8080   # 200 with x-user-id: dev-user
curl -i localhost:8080                                        # 401
```
