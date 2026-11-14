// A custom authorizer for a Gantry gateway. The gateway sends it the headers of every request on a route that uses
// it; a 2xx answer lets the request through, anything else is returned to the caller as it is.
const http = require("http");

const port = Number(process.env.PORT || 8080);

// Decide here. Return the identity headers to pass on to the app, or null to refuse the request.
function authorize({ method, uri, authorization }) {
  if (authorization === "Bearer dev-token") {
    return { "x-user-id": "dev-user" };
  }
  return null;
}

const server = http.createServer((req, res) => {
  req.resume();
  const identity = authorize({
    method: req.headers["x-forwarded-method"] || "",
    uri: req.headers["x-forwarded-uri"] || "",
    authorization: req.headers["authorization"] || "",
  });
  if (identity) {
    res.writeHead(200, identity);
    res.end();
    return;
  }
  const body = JSON.stringify({ error: "unauthorized" });
  res.writeHead(401, { "content-type": "application/json", "content-length": Buffer.byteLength(body) });
  res.end(body);
});

server.listen(port, () => console.log(`authorizer listening on :${port}`));
process.on("SIGTERM", () => server.close(() => process.exit(0)));
