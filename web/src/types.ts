export interface OptionMeta {
  type: "text" | "boolean" | "path";
  help: string;
  secret?: boolean;
}

export interface CatalogService {
  id: string;
  description: string;
  options: Record<string, string>;
  optionOrder: string[];
  optionMeta: Record<string, OptionMeta>;
  requires: string[];
  /** Port name -> preferred host port; the real one is allocated per app. */
  ports: Record<string, number>;
  /** Default env keys the service contributes. */
  env: string[];
  /** Built-in explorer route (postgres, mysql, cache, queue), "external", or the service whose UI it uses. */
  ui: string | null;
}

export interface Catalog {
  services: CatalogService[];
  presets: string[];
}

export interface AppSummary {
  name: string;
  services: string[];
  /** How many of its services have a running container. */
  running: number;
  /** Service id -> its own web UI, for services that link out instead of having a built-in explorer. */
  externalUis: Record<string, string>;
}

export type UILink =
  | { kind: "external"; url: string; note?: string }
  | { kind: "builtin"; name: "postgres" | "mysql" | "queue" }
  | { kind: "hint"; note: string };

export interface ServiceStatus {
  id: string;
  description: string;
  options: Record<string, string>;
  ports: Record<string, number>;
  state: string;
  health: string;
  ui: UILink | null;
}

/** After adding, changing or removing a service: applied when the running app was updated at once. */
export interface ServiceChange {
  applied: boolean;
  notes: string[];
}

export interface AppStatus {
  name: string;
  services: ServiceStatus[];
}

export interface TableResult {
  columns: string[];
  rows: (string | null)[][];
  /** Rows were left out (row or size cap). */
  truncated: boolean;
  note?: string;
  /** [row, column] of values cut at 64 KB; never edit these back from the shown text. */
  clippedCells: [number, number][];
}

export interface QueueSummary {
  name: string;
  visible: string;
  inFlight: string;
  delayed: string;
  redrive: string;
}

export interface QueueMessage {
  id: string;
  body: string;
  receiveCount: number;
}

export interface EnvEntry {
  service: string;
  key: string;
  name: string;
  value: string;
  omitted: boolean;
}

export interface EnvResponse {
  entries: EnvEntry[];
  text: string;
}

/** SQL services the database explorer supports; also the route segment (#/apps/x/postgres). */
export type SqlService = "postgres" | "mysql";

export interface TableSummary {
  schema: string;
  name: string;
  kind: string;
  estimatedRows: number;
  sizeBytes: number;
}

export interface DatabaseInfo {
  dialect: SqlService;
  version: string;
  database: string;
  sizeBytes: number;
  /** Postgres has schemas inside the database; for MySQL the database is the schema. */
  hasSchemas: boolean;
  schemas: string[];
  tables: TableSummary[];
}

export interface ColumnInfo {
  name: string;
  type: string;
  nullable: boolean;
  /** SQL expression, e.g. now() or 'abc'. */
  default: string | null;
  primaryKey: boolean;
  /** e.g. AUTO_INCREMENT, GENERATED ALWAYS AS IDENTITY. */
  extra: string;
}

export interface IndexInfo {
  name: string;
  unique: boolean;
  primary: boolean;
  columns: string;
}

export interface ConstraintInfo {
  name: string;
  kind: string;
  definition: string;
}

export interface TableDetail {
  schema: string;
  name: string;
  kind: string;
  rowCount: number | null;
  columns: ColumnInfo[];
  indexes: IndexInfo[];
  constraints: ConstraintInfo[];
}

export interface RowsPage {
  result: TableResult;
  primaryKey: string[];
  total: number | null;
}

export interface RowsQuery {
  offset: number;
  limit: number;
  order?: string;
  desc?: boolean;
  where?: string;
}

/** A column as typed in the UI; type, default and extra are SQL fragments. */
export interface ColumnDef {
  name: string;
  type: string;
  nullable: boolean;
  default?: string | null;
  extra?: string | null;
  primaryKey?: boolean;
}

export type DdlOp =
  | "create_table"
  | "drop_table"
  | "truncate_table"
  | "rename_table"
  | "add_column"
  | "drop_column"
  | "alter_column"
  | "create_index"
  | "drop_index"
  | "create_schema"
  | "drop_schema";

/** preview: only return the SQL; execute: run it; migration: write it as the next Flyway file. */
export type DdlMode = "preview" | "execute" | "migration";

export interface DdlRequest {
  op: DdlOp;
  mode?: DdlMode;
  schema?: string;
  table?: string;
  newName?: string;
  column?: string;
  columns?: ColumnDef[];
  definition?: ColumnDef;
  index?: string;
  indexColumns?: string[];
  unique?: boolean;
  cascade?: boolean;
  description?: string;
}

export interface DdlResult {
  sql: string;
  executed: boolean;
  migration: string | null;
}

export interface MigrationRow {
  version: string | null;
  description: string;
  type: string;
  script: string;
  /** Flyway state, e.g. Success, Pending, Failed, Missing, Baseline, Outdated. */
  state: string;
  installedOn: string | null;
  executionMs: number | null;
}

export interface MigrationStatus {
  service: SqlService;
  location: string;
  locationExists: boolean;
  current: string | null;
  pending: number;
  migrations: MigrationRow[];
}

export type MigrationAction = "migrate" | "validate" | "repair" | "baseline" | "clean";

export interface MigrationOutcome {
  message: string;
  status: MigrationStatus;
}

/** The remote database a service syncs from; the password is never part of it. */
export interface SourceSettings {
  host: string;
  port: number;
  database: string;
  user: string;
}

export interface ExportedService {
  options: Record<string, string>;
  migrations?: { repo?: string | null; path: string };
  source?: SourceSettings | null;
}

export interface ExportFile {
  gantry: number;
  app: string;
  services: Record<string, ExportedService>;
  envNames: Record<string, string>;
}

/** `options` is complete; `migrations` is an absolute path or "". */
export interface ImportedService {
  options: Record<string, string>;
  source?: SourceSettings | null;
}

export interface ImportRequest {
  app: string;
  services: Record<string, ImportedService>;
  envNames: Record<string, string>;
}

export interface ImportPreview {
  request: ImportRequest;
  exists: boolean;
  /** "service.option" of secrets taken from the existing app. */
  keptSecrets: string[];
  warnings: string[];
  checkoutGuesses: Record<string, string>;
}

export interface ImportValidation {
  errors: string[];
  exists: boolean;
}

export interface SyncJob {
  id: string;
  app: string;
  service: string;
  phase: string;
  state: string;
  log: string[];
  message: string | null;
  startedAt: string;
  tables?: number | null;
  flywayVersion?: string | null;
  /** The local database is half-copied, by this sync or an earlier one. */
  incomplete?: boolean;
  /** The tables this sync copies; empty for a whole-database sync. */
  scope?: string[];
}

export interface SourceInfo {
  source: SourceSettings | null;
  passwordSaved: boolean;
  job: SyncJob | null;
}
