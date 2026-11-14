import type {
  AppStatus,
  AppSummary,
  Catalog,
  DatabaseInfo,
  DdlRequest,
  DdlResult,
  EnvResponse,
  DbQuery,
  GatewayCall,
  GatewayConfig,
  GatewayConfigForm,
  ImportApplied,
  ImportPreview,
  ImportRequest,
  ImportValidation,
  MigrationAction,
  MigrationOutcome,
  MigrationStatus,
  PushedAuthorizer,
  QueueMessage,
  QueueSummary,
  RowsPage,
  RowsQuery,
  ServiceChange,
  SourceInfo,
  SourceSettings,
  SocketStatus,
  SqlService,
  SyncJob,
  TableDetail,
  TableResult,
} from "./types";

/** A failed request that keeps the HTTP status and the parsed body, for callers that react to specific fields. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly body: Record<string, unknown>,
  ) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(`/api${path}`, {
    method,
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(data.error ?? `${method} ${path} failed (${res.status})`, res.status, data);
  return data as T;
}

const get = <T>(path: string) => request<T>("GET", path);
const post = <T = { ok: boolean }>(path: string, body: unknown = {}) => request<T>("POST", path, body);

const app = (name: string) => `/apps/${encodeURIComponent(name)}`;

export const api = {
  catalog: () => get<Catalog>("/catalog"),
  apps: () => get<AppSummary[]>("/apps"),
  createApp: (name: string, preset: string, services: string[]) => post("/apps", { name, preset, services }),
  status: (name: string) => get<AppStatus>(app(name)),
  addService: (name: string, service: string, options: Record<string, string>, freshData = false) =>
    post<ServiceChange>(`${app(name)}/services`, { service, options, freshData }),
  leftoverVolume: (name: string, service: string) => get<{ volume: string | null }>(`${app(name)}/services/${service}/leftover`),
  removeService: (name: string, service: string) => request<ServiceChange>("DELETE", `${app(name)}/services/${service}`),
  up: (name: string) => post<{ ok: boolean; notes: string[] }>(`${app(name)}/up`),
  down: (name: string) => post(`${app(name)}/down`),
  destroy: (name: string) => post(`${app(name)}/destroy`),
  env: (name: string) => get<EnvResponse>(`${app(name)}/env`),
  setEnvNames: (name: string, names: Record<string, string>) => request<EnvResponse>("PUT", `${app(name)}/env-names`, { names }),
  logs: (name: string, service: string) => get<{ logs: string }>(`${app(name)}/logs/${service}?tail=300`),

  queues: (name: string) => get<QueueSummary[]>(`${app(name)}/queue/queues`),
  peek: (name: string, queue: string) => get<QueueMessage[]>(`${app(name)}/queue/${encodeURIComponent(queue)}/messages`),
  send: (name: string, queue: string, body: string) => post(`${app(name)}/queue/${encodeURIComponent(queue)}/send`, { body }),
  purge: (name: string, queue: string) => post(`${app(name)}/queue/${encodeURIComponent(queue)}/purge`),
  redrive: (name: string, source: string) => post<{ moved: number }>(`${app(name)}/queue/${encodeURIComponent(source)}/redrive`),

  gatewayCalls: (name: string, filter: { status?: string; path?: string; limit?: number }) =>
    get<GatewayCall[]>(`${app(name)}/gateway/calls?${qs(filter)}`),
  gatewaySockets: (name: string) => get<SocketStatus>(`${app(name)}/gateway/sockets`),
  clearGatewayCalls: (name: string) => request<{ ok: boolean }>("DELETE", `${app(name)}/gateway/calls`),
  gatewayConfig: (name: string) => get<{ text: string }>(`${app(name)}/gateway/config`),
  saveGatewayConfig: (name: string, text: string) => request<{ ok: boolean }>("PUT", `${app(name)}/gateway/config`, { text }),
  gatewayConfigForm: (name: string) => get<GatewayConfigForm>(`${app(name)}/gateway/config/form`),
  saveGatewayConfigForm: (name: string, config: GatewayConfig) =>
    request<{ ok: boolean }>("PUT", `${app(name)}/gateway/config/form`, { config }),
  gatewayAuthorizers: (name: string) => get<PushedAuthorizer[]>(`${app(name)}/gateway/authorizers`),

  exportUrl: (name: string) => `/api${app(name)}/export`,
  importPreview: (file: unknown, as?: string) => post<ImportPreview>("/import/preview", { file, as: as || undefined }),
  importValidate: (req: ImportRequest) => post<ImportValidation>("/import/validate", req),
  importApply: (req: ImportRequest) => post<ImportApplied>("/import/apply", req),

  db: (name: string, service: SqlService) => dbApi(`${app(name)}/db/${service}`),
};

const qs = (params: Record<string, string | number | boolean | undefined>) =>
  Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== "")
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`)
    .join("&");

function dbApi(base: string) {
  const table = (schema: string, name: string) => qs({ schema, name });
  return {
    info: () => get<DatabaseInfo>(`${base}/info`),
    queries: (filter: { search?: string; from?: string; to?: string; limit?: number }) => get<DbQuery[]>(`${base}/queries?${qs(filter)}`),
    query: (id: string) => get<DbQuery>(`${base}/queries/${encodeURIComponent(id)}`),
    clearQueries: () => request<{ ok: boolean }>("DELETE", `${base}/queries`),
    table: (schema: string, name: string) => get<TableDetail>(`${base}/table?${table(schema, name)}`),
    rows: (schema: string, name: string, q: RowsQuery) => get<RowsPage>(`${base}/rows?${table(schema, name)}&${qs({ ...q })}`),
    insert: (schema: string, name: string, values: Record<string, string | null>) =>
      post(`${base}/rows/insert`, { schema, table: name, values }),
    update: (schema: string, name: string, key: Record<string, string | null>, values: Record<string, string | null>) =>
      post(`${base}/rows/update`, { schema, table: name, key, values }),
    remove: (schema: string, name: string, keys: Record<string, string | null>[]) =>
      post<{ count: number }>(`${base}/rows/delete`, { schema, table: name, keys }),
    sql: (sql: string) => post<TableResult>(`${base}/sql`, { sql }),
    ddl: (request: DdlRequest) => post<DdlResult>(`${base}/ddl`, request),
    migrations: () => get<MigrationStatus>(`${base}/migrations`),
    migrate: (action: MigrationAction) => post<MigrationOutcome>(`${base}/migrations/${action}`),
    newMigration: (description: string, sql: string) => post<{ name: string; sql: string }>(`${base}/migrations/new`, { description, sql }),
    source: () => get<SourceInfo>(`${base}/source`),
    savedPassword: () => get<{ password: string | null }>(`${base}/source/password`),
    saveSource: (source: SourceSettings, password?: string) =>
      request<SourceInfo>("PUT", `${base}/source`, { ...source, password: password || undefined }),
    startSync: (source: SourceSettings, password?: string, tables?: string[]) =>
      post<SyncJob>(`${base}/sync`, { source, password: password || undefined, tables }),
    syncJob: () => get<SyncJob>(`${base}/sync`),
    script: (scriptName: string) => get<{ name: string; sql: string }>(`${base}/migrations/script?${qs({ name: scriptName })}`),
  };
}
