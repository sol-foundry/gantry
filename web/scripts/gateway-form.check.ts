// Checks the gateway form's model without a browser: `npm run check:gateway-form` (Node strips the types).
import assert from "node:assert/strict";
import { canonical, copyName, duplicateRoute, fromConfig, newAuthorizer, newId, removeNamed, toConfig } from "../src/components/gateway/gatewayForm.ts";
import type { GatewayAuthorizer, GatewayConfig, GatewayRoute } from "../src/types.ts";

const authorizer = (over: Partial<GatewayAuthorizer>): GatewayAuthorizer => ({
  type: "firebase",
  projectId: null,
  region: null,
  userPoolId: null,
  clientIds: [],
  tokenUse: "id",
  url: null,
  jwtSecret: null,
  jwksUrl: null,
  jwks: null,
  issuer: null,
  audiences: [],
  algorithms: ["RS256"],
  container: null,
  env: {},
  tokenFrom: "header:Authorization",
  onUnverifiedEmail: "deny",
  denyStatus: 403,
  missingTokenStatus: null,
  ...over,
});

const route = (name: string, auth: string, over: Partial<GatewayRoute> = {}): GatewayRoute => ({
  name,
  match: { host: "*", path: `/${name}/**`, methods: [] },
  auth,
  steps: [],
  rewrite: null,
  upstream: null,
  headers: {},
  ...over,
});

const base = (): GatewayConfig => ({
  upstream: null,
  headers: {},
  authorizers: { fb: authorizer({ projectId: "p" }) },
  steps: {
    user: {
      type: "http-lookup",
      lookup: { method: "GET", url: "{{upstream}}/u", headers: {}, body: {} },
      create: { method: "POST", url: "{{upstream}}/u", headers: {}, body: {} },
      map: { userId: "$.id" },
      cacheSeconds: 300,
      onFailure: "deny",
    },
  },
  routes: [route("pub", "public"), route("blocked", "deny"), route("portal", "fb", { steps: ["user"] })],
  default: { auth: "fb", steps: ["user"], headers: {}, upstream: null },
  cors: null,
  stripHeaders: ["x-user-id", "x-gateway-user-email", "x-gateway-auth-strategy", "x-internal-api-key", "x-error-code"],
  log: { maxCalls: 1000, maxBodyBytes: 65536, redact: ["authorization", "cookie", "set-cookie", "x-internal-api-key"] },
  websocket: {
    auth: "fb",
    steps: ["user"],
    headers: { "x-user-id": "{{userId}}" },
    connect: "{{upstream}}/connect/{{connectionId}}",
    disconnect: null,
    routeSelection: "$request.body.action",
    routes: { $default: "{{upstream}}/ws/messages/{{connectionId}}" },
  },
});

let failed = 0;
function check(name: string, body: () => void) {
  try {
    body();
    console.log(`ok   ${name}`);
  } catch (e) {
    failed++;
    console.log(`FAIL ${name}\n${(e as Error).message}`);
  }
}

check("a config reads back unchanged, explicit empties and odd names included", () => {
  const c = base();
  c.headers = { " spaced ": "v", __proto__x: "a", "x-list": "a,b" };
  Object.defineProperty(c.headers, "__proto__", { value: "p", enumerable: true, configurable: true, writable: true });
  c.authorizers.c = authorizer({ type: "custom", container: "check", url: "http://h:9/x", algorithms: [], env: { A: "{{secret:A}}" } });
  c.stripHeaders = [];
  c.log.redact = [];
  c.cors = { origins: ["http://a:1"], credentials: true, allowHeaders: ["x-a, x-b"], exposeHeaders: [], maxAge: 5 };
  c.routes.push(route("rw", "public", { rewrite: { addPrefix: "", stripPrefix: null, replace: null }, match: { host: "h.example", path: "/x", methods: ["get"] } }));
  const back = toConfig(fromConfig(c));
  assert.deepEqual(back.problems, []);
  assert.equal(canonical(back.config), canonical(c));
  assert.equal(Object.getPrototypeOf(back.config.headers), Object.prototype);
  assert.equal(Object.hasOwn(back.config.headers, "__proto__"), true);
});

check("new authorizers named public-api and deny-x leave Public and Blocked routes alone", () => {
  const form = fromConfig(base());
  for (const name of ["p", "pu", "public", "public-api", "d", "deny", "deny-x"]) {
    const a = newAuthorizer();
    a.name = name;
    a.projectId = "x";
    form.authorizers = [...form.authorizers.filter((x) => x.name !== name.slice(0, -1)), a];
  }
  const out = toConfig(form).config;
  assert.deepEqual(
    out.routes.map((r) => r.auth),
    ["public", "deny", "fb"],
  );
  assert.equal(out.default.auth, "fb");
});

check("renaming an authorizer or step carries its routes and the default with it", () => {
  const form = fromConfig(base());
  form.authorizers[0].name = "firebase-admin";
  form.steps[0].name = "lookup";
  const out = toConfig(form).config;
  assert.equal(out.routes[2].auth, "firebase-admin");
  assert.deepEqual(out.routes[2].steps, ["lookup"]);
  assert.equal(out.default.auth, "firebase-admin");
  assert.deepEqual(out.default.steps, ["lookup"]);
  assert.deepEqual(Object.keys(out.authorizers), ["firebase-admin"]);
});

check("a cleared or reserved authorizer name is a problem, not a silent rewrite", () => {
  const form = fromConfig(base());
  form.authorizers[0].name = "";
  const cleared = toConfig(form);
  assert.ok(cleared.problems.some((p) => p.includes("Needs a name")));
  assert.equal(cleared.config.routes[0].auth, "public");
  form.authorizers[0].name = "public";
  assert.ok(toConfig(form).problems.some((p) => p.includes("reserved")));
});

check("duplicate names are -copy, -copy-2, ... and never over 64 characters", () => {
  assert.equal(copyName("a", ["a"]), "a-copy");
  assert.equal(copyName("a", ["a", "a-copy"]), "a-copy-2");
  assert.equal(copyName("a", ["a", "a-copy", "a-copy-2"]), "a-copy-3");
  const long = "n".repeat(64);
  const first = copyName(long, [long]);
  const second = copyName(long, [long, first]);
  assert.equal(first.length, 64);
  assert.equal(second.length, 64);
  assert.notEqual(first, second);
  const form = fromConfig(base());
  const copy = duplicateRoute(form.routes[2], form.routes);
  assert.equal(copy.name, "portal-copy");
  assert.notEqual(copy.id, form.routes[2].id);
});

check("empty list items in the file are kept, so a check is never turned off", () => {
  const c = base();
  c.authorizers.fb = authorizer({ projectId: "p", audiences: [""], clientIds: ["a", ""] });
  c.cors = { origins: ["http://a:1"], credentials: false, allowHeaders: [""], exposeHeaders: [], maxAge: 600 };
  const back = toConfig(fromConfig(c));
  assert.deepEqual(back.problems, []);
  assert.equal(canonical(back.config), canonical(c));
  assert.deepEqual(back.config.authorizers.fb.audiences, [""]);
});

check("a blank item added in the form is a problem, not dropped", () => {
  const form = fromConfig(base());
  form.authorizers[0].audiences = [...form.authorizers[0].audiences, { id: newId(), value: "" }];
  const out = toConfig(form);
  assert.ok(out.problems.some((p) => p.includes("audiences: an item is empty")), out.problems.join("; "));
});

check("a loaded \"\" stays \"\"; a field the user clears becomes unset", () => {
  const c = base();
  c.upstream = "";
  c.authorizers.fb = authorizer({ projectId: "p", issuer: "" });
  c.routes[0] = route("pub", "public", { upstream: "", match: { host: "", path: "/pub/**", methods: [] } });
  const form = fromConfig(c);
  const same = toConfig(form);
  assert.deepEqual(same.problems, []);
  assert.equal(canonical(same.config), canonical(c));
  form.authorizers[0].projectId = "";
  form.routes[2].upstream = "";
  const cleared = toConfig(form).config;
  assert.equal(cleared.authorizers.fb.projectId, null);
  assert.equal(cleared.authorizers.fb.issuer, "");
  assert.equal(cleared.upstream, "");
  assert.equal(cleared.routes[0].match.host, "");
});

check("a route left on a deleted authorizer or step, or on Login with none, is a problem before saving", () => {
  let form = fromConfig(base());
  form = removeNamed(form, "authorizer", form.authorizers[0].id);
  form = removeNamed(form, "step", form.steps[0].id);
  const out = toConfig(form);
  assert.ok(out.problems.includes("route 'portal': authorizer 'fb' was deleted"), out.problems.join("; "));
  assert.ok(out.problems.includes("route 'portal': step 'user' was deleted"), out.problems.join("; "));
  assert.ok(out.problems.includes("requests that match no route: authorizer 'fb' was deleted"), out.problems.join("; "));
  assert.equal(out.config.routes[2].auth, "fb");
  const none = fromConfig(base());
  none.routes[0] = { ...none.routes[0], access: "login", authorizer: "" };
  assert.ok(toConfig(none).problems.includes("route 'pub': pick an authorizer"));
  const undefinedRef = base();
  undefinedRef.routes[0].auth = "nobody";
  assert.ok(toConfig(fromConfig(undefinedRef)).problems.includes("route 'pub': authorizer 'nobody' is not defined"));
});

if (failed > 0) {
  console.log(`${failed} failed`);
  process.exit(1);
}
console.log("all passed");
