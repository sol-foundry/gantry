import type { GatewayAuthorizer, GatewayConfig, GatewayHttpCall, GatewayRoute, GatewayStep } from "@/types";

/**
 * The form's working copy of gateway.yml, faithful to the file: what the form loads it writes back unchanged.
 * Maps become ordered rows and lists one row per item; numbers stay text while they are typed. Routes and the
 * default point at authorizers and steps by form id, so a rename is just a rename. [toConfig] turns it back.
 */

/** The headers the gateway removes from every request unless stripHeaders says otherwise (server default). */
export const DEFAULT_STRIP = ["x-user-id", "x-gateway-user-email", "x-gateway-auth-strategy", "x-internal-api-key", "x-error-code"];
export const DEFAULT_REDACT = ["authorization", "cookie", "set-cookie", "x-internal-api-key"];
export const COMMON_METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"];
/** Route `auth` values that are access kinds, not authorizers; an authorizer may not use them as its name. */
export const RESERVED_AUTH = ["public", "deny"];
/** The server's limit for a route name. */
export const MAX_ROUTE_NAME = 64;
export const AUTHORIZER_TYPES = [
  { value: "firebase", label: "Firebase" },
  { value: "cognito", label: "Cognito" },
  { value: "supabase", label: "Supabase" },
  { value: "jwt", label: "JWT" },
  { value: "custom", label: "Custom" },
];

let lastId = 0;
/** A key for React, and the handle routes use for authorizers and steps; it survives renames and reordering. */
export const newId = () => ++lastId;

export interface Pair {
  id: number;
  name: string;
  value: string;
}

export interface Item {
  id: number;
  value: string;
  /** The file had this item as "" (kept as it is); a blank item added in the form is a problem instead. */
  loadedEmpty?: boolean;
}

/** An authorizer or step: its form id, or a name the form does not define (kept as written). */
export type Ref = number | string;

export type Access = "public" | "login" | "deny";

export interface FormCall {
  method: string;
  url: string;
  headers: Pair[];
  /** null: no body; an empty list is an explicit empty body. */
  body: Pair[] | null;
}

export type RewriteKind = "none" | "addPrefix" | "stripPrefix" | "replace";

export interface FormRewrite {
  /** The kind the select shows; stored, so clearing the text does not hide the field. */
  kind: RewriteKind;
  addPrefix: string | null;
  stripPrefix: string | null;
  replace: string | null;
  /** The file had `rewrite:` with nothing in it. */
  empty: boolean;
}

export interface FormAction {
  access: Access;
  /** For Login: the authorizer. */
  authorizer: Ref;
  steps: Ref[];
}

export interface FormRoute extends FormAction {
  id: number;
  name: string;
  path: string;
  host: string;
  methods: string[];
  rewrite: FormRewrite;
  upstream: string;
  headers: Pair[];
  /** Fields the file had as "", see [blanks]. */
  loadedBlank: string[];
}

export interface FormAuthorizer {
  id: number;
  name: string;
  type: string;
  projectId: string;
  region: string;
  userPoolId: string;
  clientIds: Item[];
  tokenUse: string;
  url: string;
  jwtSecret: string;
  jwksUrl: string;
  /** An inline key set is kept as it came; the form does not edit it. */
  jwks: Record<string, unknown> | null;
  issuer: string;
  audiences: Item[];
  algorithms: Item[];
  container: string;
  env: Pair[];
  tokenFrom: string;
  onUnverifiedEmail: string;
  denyStatus: string;
  missingTokenStatus: string;
  /** Fields the file had as "", see [blanks]. */
  loadedBlank: string[];
}

export interface FormStep {
  id: number;
  name: string;
  type: string;
  lookup: FormCall;
  hasCreate: boolean;
  create: FormCall;
  map: Pair[];
  cacheSeconds: string;
  onFailure: string;
}

export interface FormCors {
  origins: Item[];
  credentials: boolean;
  allowHeaders: Item[];
  exposeHeaders: Item[];
  maxAge: string;
}

/** A list with a server default: either "use the default" or exactly these items (possibly none). */
export interface DefaultableList {
  useDefault: boolean;
  items: Item[];
}

export interface FormState {
  upstream: string;
  headers: Pair[];
  authorizers: FormAuthorizer[];
  steps: FormStep[];
  routes: FormRoute[];
  /** The default action; its headers and upstream are not on the form and are kept as they came. */
  default: FormAction & { headers: Record<string, string>; upstream: string | null };
  corsOn: boolean;
  cors: FormCors;
  stripHeaders: DefaultableList;
  log: { maxCalls: string; maxBodyBytes: string; redact: DefaultableList };
  /** WebSocket clients; headers, routes and route selection are not on the form and are kept as they came. */
  websocket: FormAction & {
    headers: Record<string, string>;
    connect: string;
    disconnect: string;
    routeSelection: string;
    routes: Record<string, string>;
  };
  /** Top-level fields the file had as "", see [blanks]. */
  loadedBlank: string[];
  /** Names of authorizers and steps deleted in the form, to say so when a route still uses one. */
  deleted: { authorizers: string[]; steps: string[] };
}

const pairs = (map: Record<string, string> | null | undefined): Pair[] =>
  Object.entries(map ?? {}).map(([name, value]) => ({ id: newId(), name, value }));
export const items = (list: string[]): Item[] => list.map((value) => (value === "" ? { id: newId(), value, loadedEmpty: true } : { id: newId(), value }));

/** The names of [fields] that were loaded as "": kept as "" on save, where a field the user clears becomes unset. */
function blanks<T extends object>(o: T, fields: (keyof T)[]): string[] {
  return fields.filter((f) => o[f] === "").map(String);
}
const text = (value: string | null | undefined) => value ?? "";

const formCall = (call: GatewayHttpCall | null, method = "GET"): FormCall => ({
  method: call?.method ?? method,
  url: text(call?.url),
  headers: pairs(call?.headers),
  body: call?.body == null ? null : pairs(call.body),
});

const defaultable = (list: string[], fallback: string[]): DefaultableList =>
  sameList(list, fallback) ? { useDefault: true, items: items(fallback) } : { useDefault: false, items: items(list) };

/** `auth` and `steps` as the form holds them, with names resolved to form ids where they are defined. */
function action(auth: string, steps: string[], authorizerIds: Map<string, number>, stepIds: Map<string, number>): FormAction {
  const access: Access = auth === "public" ? "public" : auth === "deny" ? "deny" : "login";
  return {
    access,
    authorizer: access === "login" ? (authorizerIds.get(auth) ?? auth) : "",
    steps: steps.map((s) => stepIds.get(s) ?? s),
  };
}

function rewrite(r: GatewayRoute["rewrite"]): FormRewrite {
  const values = { addPrefix: r?.addPrefix ?? null, stripPrefix: r?.stripPrefix ?? null, replace: r?.replace ?? null };
  const kind: RewriteKind = values.addPrefix != null ? "addPrefix" : values.stripPrefix != null ? "stripPrefix" : values.replace != null ? "replace" : "none";
  return { kind, ...values, empty: r != null && kind === "none" };
}

function fromRoute(r: GatewayRoute, authorizerIds: Map<string, number>, stepIds: Map<string, number>): FormRoute {
  return {
    id: newId(),
    name: r.name,
    path: r.match.path,
    host: r.match.host === "*" ? "" : r.match.host,
    methods: r.match.methods,
    ...action(r.auth, r.steps, authorizerIds, stepIds),
    rewrite: rewrite(r.rewrite),
    upstream: text(r.upstream),
    headers: pairs(r.headers),
    loadedBlank: [...blanks(r, ["upstream"]), ...blanks(r.match, ["host", "path"])],
  };
}

function fromAuthorizer(name: string, a: GatewayAuthorizer): FormAuthorizer {
  return {
    id: newId(),
    name,
    type: a.type,
    projectId: text(a.projectId),
    region: text(a.region),
    userPoolId: text(a.userPoolId),
    clientIds: items(a.clientIds),
    tokenUse: a.tokenUse,
    url: text(a.url),
    jwtSecret: text(a.jwtSecret),
    jwksUrl: text(a.jwksUrl),
    jwks: a.jwks,
    issuer: text(a.issuer),
    audiences: items(a.audiences),
    algorithms: items(a.algorithms),
    container: text(a.container),
    env: pairs(a.env),
    tokenFrom: a.tokenFrom,
    onUnverifiedEmail: a.onUnverifiedEmail,
    denyStatus: String(a.denyStatus),
    missingTokenStatus: a.missingTokenStatus == null ? "" : String(a.missingTokenStatus),
    loadedBlank: blanks(a, ["projectId", "region", "userPoolId", "url", "jwtSecret", "jwksUrl", "issuer", "container"]),
  };
}

function fromStep(name: string, s: GatewayStep): FormStep {
  return {
    id: newId(),
    name,
    type: s.type,
    lookup: formCall(s.lookup),
    hasCreate: s.create != null,
    // A new create call usually makes the user, so it starts as a POST.
    create: formCall(s.create, "POST"),
    map: pairs(s.map),
    cacheSeconds: String(s.cacheSeconds),
    onFailure: s.onFailure,
  };
}

export function fromConfig(c: GatewayConfig): FormState {
  // An older server sends no websocket block.
  const ws = c.websocket ?? { auth: "public", steps: [], headers: {}, connect: null, disconnect: null, routeSelection: "$request.body.action", routes: {} };
  const authorizers = Object.entries(c.authorizers).map(([name, a]) => fromAuthorizer(name, a));
  const steps = Object.entries(c.steps).map(([name, s]) => fromStep(name, s));
  const authorizerIds = new Map(authorizers.map((a) => [a.name, a.id]));
  const stepIds = new Map(steps.map((s) => [s.name, s.id]));
  return {
    upstream: text(c.upstream),
    headers: pairs(c.headers),
    authorizers,
    steps,
    routes: c.routes.map((r) => fromRoute(r, authorizerIds, stepIds)),
    default: { ...action(c.default.auth, c.default.steps, authorizerIds, stepIds), headers: c.default.headers, upstream: c.default.upstream },
    corsOn: c.cors != null,
    cors: {
      origins: items(c.cors?.origins ?? ["*"]),
      credentials: c.cors?.credentials ?? false,
      allowHeaders: items(c.cors?.allowHeaders ?? []),
      exposeHeaders: items(c.cors?.exposeHeaders ?? []),
      maxAge: String(c.cors?.maxAge ?? 600),
    },
    stripHeaders: defaultable(c.stripHeaders, DEFAULT_STRIP),
    log: {
      maxCalls: String(c.log.maxCalls),
      maxBodyBytes: String(c.log.maxBodyBytes),
      redact: defaultable(c.log.redact, DEFAULT_REDACT),
    },
    websocket: {
      ...action(ws.auth, ws.steps, authorizerIds, stepIds),
      headers: ws.headers,
      connect: text(ws.connect),
      disconnect: text(ws.disconnect),
      routeSelection: ws.routeSelection,
      routes: ws.routes,
    },
    loadedBlank: blanks(c, ["upstream"]),
    deleted: { authorizers: [], steps: [] },
  };
}

export const newRoute = (): FormRoute => ({
  id: newId(),
  name: "",
  path: "/",
  host: "",
  methods: [],
  access: "public",
  authorizer: "",
  steps: [],
  rewrite: { kind: "none", addPrefix: null, stripPrefix: null, replace: null, empty: false },
  upstream: "",
  headers: [],
  loadedBlank: [],
});

export const newAuthorizer = (): FormAuthorizer =>
  fromAuthorizer("", {
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
  });

export const newStep = (): FormStep =>
  fromStep("", { type: "http-lookup", lookup: { method: "GET", url: "", headers: {}, body: null }, create: null, map: {}, cacheSeconds: 300, onFailure: "deny" });

export const newPair = (): Pair => ({ id: newId(), name: "", value: "" });

/** `<name>-copy`, then `-copy-2`, `-copy-3`, ...: the first not in [taken], cut to the server's 64 characters. */
export function copyName(name: string, taken: Iterable<string>): string {
  const used = new Set(taken);
  for (let n = 1; ; n++) {
    const suffix = n === 1 ? "-copy" : `-copy-${n}`;
    const candidate = name.slice(0, MAX_ROUTE_NAME - suffix.length) + suffix;
    if (!used.has(candidate)) return candidate;
  }
}

/** A copy of [route] with fresh ids and a unique name among [routes]. */
export const duplicateRoute = (route: FormRoute, routes: FormRoute[]): FormRoute => ({
  ...route,
  id: newId(),
  name: copyName(route.name, routes.map((r) => r.name)),
  rewrite: { ...route.rewrite },
  headers: route.headers.map((h) => ({ ...h, id: newId() })),
});

function sameList(a: string[], b: string[]) {
  return a.length === b.length && a.every((x, i) => x === b[i]);
}

/** `header:<name>` or `query:<name>` split for the form. */
export function tokenSource(value: string): { kind: "header" | "query"; name: string } {
  const at = value.indexOf(":");
  const kind = value.slice(0, at);
  return kind === "query" || kind === "header" ? { kind, name: value.slice(at + 1) } : { kind: "header", name: value };
}

/** Why an authorizer or step name can't be used, or undefined when it can. */
export function nameProblem(name: string, others: string[], what: "authorizer" | "step"): string | undefined {
  if (!name) return "Needs a name.";
  if (what === "authorizer" && RESERVED_AUTH.includes(name)) return `'${name}' is reserved; pick another name.`;
  if (others.includes(name)) return "Another one already has this name.";
  return undefined;
}

/** The name an id points at now, or the name kept as written. */
export function refName(ref: Ref, named: { id: number; name: string }[]): string {
  return typeof ref === "number" ? (named.find((n) => n.id === ref)?.name ?? "") : ref;
}

/** What the form turns into, plus problems it can tell before asking the server. */
export interface Converted {
  config: GatewayConfig;
  problems: string[];
}

/** The form as a GatewayConfig. Blank optional text is null; nothing else is changed from what was loaded. */
export function toConfig(s: FormState): Converted {
  const problems: string[] = [];

  // Map keeps "__proto__" and friends as plain names; fromEntries then defines them as own properties.
  const map = (rows: Pair[], where: string, what = "header"): Record<string, string> => {
    const out = new Map<string, string>();
    for (const row of rows) {
      if (row.name === "" && row.value === "") continue;
      if (row.name.trim() === "") problems.push(`${where}: a ${what} has a value but no name`);
      else if (out.has(row.name)) problems.push(`${where}: ${what} '${row.name}' is listed twice`);
      else out.set(row.name, row.value);
    }
    return Object.fromEntries(out);
  };
  const list = (rows: Item[], where: string) => {
    if (rows.some((r) => r.value === "" && !r.loadedEmpty)) problems.push(`${where}: an item is empty; fill it in or remove it`);
    return rows.map((r) => r.value);
  };
  /** "" is unset (null), unless the file itself had "" there. */
  const opt = (value: string, field: string, loadedBlank: string[]) => (value === "" ? (loadedBlank.includes(field) ? "" : null) : value);
  /** A blank box means the default, unless the file itself had "" there. */
  const orDefault = (value: string, field: string, loadedBlank: string[], fallback: string) =>
    value === "" && !loadedBlank.includes(field) ? fallback : value;
  const int = (value: string, where: string): number => {
    if (/^\d+$/.test(value)) return Number(value);
    problems.push(`${where}: '${value}' is not a whole number`);
    // Kept as typed so the form still counts as changed; never sent, since a problem stops the save.
    return value as unknown as number;
  };
  const call = (c: FormCall, where: string): GatewayHttpCall => ({
    method: c.method,
    url: c.url,
    headers: map(c.headers, where),
    body: c.body === null ? null : map(c.body, `${where} body`, "field"),
  });
  const names = <T extends { name: string }>(rows: T[], what: "authorizer" | "step") =>
    rows.forEach((row, i) => {
      const problem = nameProblem(
        row.name,
        rows.filter((_, j) => j !== i).map((r) => r.name),
        what,
      );
      if (problem) problems.push(`${what} '${row.name}': ${problem}`);
    });
  const missing = (ref: Ref, named: { id: number; name: string }[], deleted: string[], what: string, label: string) => {
    const name = refName(ref, named);
    if (typeof ref === "number" ? named.some((n) => n.id === ref) : named.some((n) => n.name === ref)) return name;
    problems.push(deleted.includes(name) ? `${label}: ${what} '${name}' was deleted` : `${label}: ${what} '${name}' is not defined`);
    return name;
  };
  const resolve = (a: FormAction, label: string): { auth: string; steps: string[] } => {
    let auth: string = a.access;
    if (a.access === "login") {
      if (a.authorizer === "") problems.push(`${label}: pick an authorizer`);
      else auth = missing(a.authorizer, s.authorizers, s.deleted.authorizers, "authorizer", label);
      if (a.authorizer === "") auth = "";
    }
    return { auth, steps: a.steps.map((ref) => missing(ref, s.steps, s.deleted.steps, "step", label)) };
  };

  names(s.authorizers, "authorizer");
  names(s.steps, "step");

  const authorizers: Record<string, GatewayAuthorizer> = {};
  for (const a of s.authorizers) {
    const label = `authorizer '${a.name}'`;
    Object.defineProperty(authorizers, a.name, {
      enumerable: true,
      configurable: true,
      writable: true,
      value: {
        type: a.type,
        projectId: opt(a.projectId, "projectId", a.loadedBlank),
        region: opt(a.region, "region", a.loadedBlank),
        userPoolId: opt(a.userPoolId, "userPoolId", a.loadedBlank),
        clientIds: list(a.clientIds, `${label} client IDs`),
        tokenUse: a.tokenUse,
        url: opt(a.url, "url", a.loadedBlank),
        jwtSecret: opt(a.jwtSecret, "jwtSecret", a.loadedBlank),
        jwksUrl: opt(a.jwksUrl, "jwksUrl", a.loadedBlank),
        jwks: a.jwks,
        issuer: opt(a.issuer, "issuer", a.loadedBlank),
        audiences: list(a.audiences, `${label} audiences`),
        algorithms: list(a.algorithms, `${label} algorithms`),
        container: opt(a.container, "container", a.loadedBlank),
        env: map(a.env, `${label} environment`, "variable"),
        tokenFrom: a.tokenFrom,
        onUnverifiedEmail: a.onUnverifiedEmail,
        denyStatus: int(a.denyStatus, `${label} deny status`),
        missingTokenStatus: a.missingTokenStatus === "" ? null : int(a.missingTokenStatus, `${label} missing-token status`),
      } satisfies GatewayAuthorizer,
    });
  }

  const steps: Record<string, GatewayStep> = {};
  for (const st of s.steps) {
    const label = `step '${st.name}'`;
    Object.defineProperty(steps, st.name, {
      enumerable: true,
      configurable: true,
      writable: true,
      value: {
        type: st.type,
        lookup: call(st.lookup, `${label} lookup`),
        create: st.hasCreate ? call(st.create, `${label} create`) : null,
        map: map(st.map, `${label} map`, "name"),
        cacheSeconds: int(st.cacheSeconds, `${label} cache seconds`),
        onFailure: st.onFailure,
      } satisfies GatewayStep,
    });
  }

  const routes: GatewayRoute[] = s.routes.map((r) => {
    const { addPrefix, stripPrefix, replace, empty } = r.rewrite;
    const any = addPrefix != null || stripPrefix != null || replace != null;
    return {
      name: r.name,
      match: {
        host: orDefault(r.host, "host", r.loadedBlank, "*"),
        path: orDefault(r.path, "path", r.loadedBlank, "/**"),
        methods: r.methods,
      },
      ...resolve(r, `route '${r.name}'`),
      rewrite: any || empty ? { addPrefix, stripPrefix, replace } : null,
      upstream: opt(r.upstream, "upstream", r.loadedBlank),
      headers: map(r.headers, `route '${r.name}'`),
    };
  });

  const config: GatewayConfig = {
    upstream: opt(s.upstream, "upstream", s.loadedBlank),
    headers: map(s.headers, "headers for all routes"),
    authorizers,
    steps,
    routes,
    default: { ...resolve(s.default, "requests that match no route"), headers: s.default.headers, upstream: s.default.upstream },
    cors: s.corsOn
      ? {
          origins: list(s.cors.origins, "CORS origins"),
          credentials: s.cors.credentials,
          allowHeaders: list(s.cors.allowHeaders, "CORS allow headers"),
          exposeHeaders: list(s.cors.exposeHeaders, "CORS expose headers"),
          maxAge: int(s.cors.maxAge, "CORS max age"),
        }
      : null,
    stripHeaders: s.stripHeaders.useDefault ? DEFAULT_STRIP : list(s.stripHeaders.items, "headers removed from incoming requests"),
    log: {
      maxCalls: int(s.log.maxCalls, "call log max calls"),
      maxBodyBytes: int(s.log.maxBodyBytes, "call log max body bytes"),
      redact: s.log.redact.useDefault ? DEFAULT_REDACT : list(s.log.redact.items, "call log hidden headers"),
    },
    websocket: {
      ...resolve(s.websocket, "WebSocket connections"),
      headers: s.websocket.headers,
      connect: s.websocket.connect.trim() === "" ? null : s.websocket.connect.trim(),
      disconnect: s.websocket.disconnect.trim() === "" ? null : s.websocket.disconnect.trim(),
      routeSelection: s.websocket.routeSelection,
      routes: s.websocket.routes,
    },
  };
  // A clash between two names is found from both sides; say it once.
  return { config, problems: [...new Set(problems)] };
}

/** JSON with object keys sorted, so two configs compare equal whatever order the server wrote their fields in. */
export function canonical(value: unknown): string {
  return JSON.stringify(value, (_, v) =>
    v && typeof v === "object" && !Array.isArray(v) ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, v[k]])) : v,
  );
}

/**
 * [s] without the authorizer or step [id]. Routes and the default that used it keep its name, so the form can say it
 * was deleted (and Save is blocked) instead of quietly pointing them elsewhere.
 */
export function removeNamed(s: FormState, kind: "authorizer" | "step", id: number): FormState {
  const name = refName(id, kind === "authorizer" ? s.authorizers : s.steps);
  const forget = <T extends FormAction>(a: T): T => {
    if (kind === "authorizer") return a.authorizer === id ? { ...a, authorizer: name } : a;
    return a.steps.includes(id) ? { ...a, steps: a.steps.map((r) => (r === id ? name : r)) } : a;
  };
  return {
    ...s,
    authorizers: kind === "authorizer" ? s.authorizers.filter((x) => x.id !== id) : s.authorizers,
    steps: kind === "step" ? s.steps.filter((x) => x.id !== id) : s.steps,
    routes: s.routes.map(forget),
    default: forget(s.default),
    websocket: forget(s.websocket),
    deleted:
      kind === "authorizer"
        ? { ...s.deleted, authorizers: [...s.deleted.authorizers, name] }
        : { ...s.deleted, steps: [...s.deleted.steps, name] },
  };
}
