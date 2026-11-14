# Gantry

**Give every microservice its own local stack.** Gantry runs real Postgres, MySQL, Valkey, SQS (ElasticMQ), Kafka
and Temporal for each of your services in Docker on your laptop, so you build and test against the real thing
before anything launches to staging or production.

Create an **app** per service, pick the services it needs and start it; Gantry hands you the connection settings
as an env block. Manage everything from the web dashboard or the `gantry` CLI, and let coding agents (Claude Code,
Cursor, Codex) look up an app's connection details themselves over MCP.

- One tool instead of hand-written compose files and per-project setup scripts.
- Every app is an isolated Docker Compose project with its own ports, so apps never clash.
- No cloud account, VPN or shared queues: everything runs on your machine.
- Built-in explorers for databases and queues; RedisInsight, the Temporal UI and Kafka UI come with their services.
- Postgres and MySQL run [Flyway](https://flywaydb.org) migrations from your service's own folder, and can be
  synced from a source database such as stage.
- A REST API gateway in front of your app checks logins (Firebase, Cognito, Supabase, JWT or your own code) and logs
  every call, so you can see exactly what your app was sent.

## Install

Gantry runs your services in Docker, so you need Docker with Compose v2 (Docker Desktop or Colima). Gantry itself
runs on Java, but you don't need to install Java: both methods below bring their own. Both work on macOS and Linux.
(On Windows, [build from source](CONTRIBUTING.md#build-from-source).)

### Script (recommended)

**Install**

```bash
curl -fsSL https://raw.githubusercontent.com/sol-foundry/gantry/main/install.sh | sh
```

That's all: open a new terminal (or run the `export PATH=...` line the script prints) and check it with
`gantry services`.

The script:

1. downloads the latest Gantry release and checks it against its published checksum;
2. installs it in `~/.gantry/dist`, next to your apps;
3. downloads a Java 17 runtime (Eclipse Temurin, checksum-checked) into `~/.gantry/runtime`, used only by Gantry;
4. creates the `gantry` command in `~/.gantry/bin` and adds that directory to `PATH` in your shell's startup file
   (`~/.zshrc`, `~/.bashrc`/`~/.bash_profile`, fish's `conf.d`, or `~/.profile`).

Settings go before `sh`, e.g. `curl -fsSL .../install.sh | GANTRY_VERSION=0.1.0 sh`:

| Variable | Effect |
|---|---|
| `GANTRY_VERSION` | install this version instead of the latest, e.g. a release candidate (`1.2.0-rc1`) |
| `GANTRY_JAVA_HOME` | use your own Java 17+ instead of downloading one |
| `GANTRY_INSTALL_DIR` | install somewhere other than `~/.gantry` |
| `GANTRY_NO_MODIFY_PATH=1` | don't touch your shell's startup file |

**Update**

Run the install command again. It replaces Gantry with the latest release and fetches a new Java runtime only when a
newer Java 17 update exists.

**Uninstall**

```bash
curl -fsSL https://raw.githubusercontent.com/sol-foundry/gantry/main/install.sh | sh -s -- --uninstall
```

This removes `~/.gantry/bin`, `~/.gantry/dist` and `~/.gantry/runtime`, and the `PATH` line it added.

### Homebrew

**Install**

```bash
brew tap sol-foundry/tap https://github.com/sol-foundry/gantry && brew trust --formula sol-foundry/tap/gantry && brew install gantry
```

One line, three steps: add Gantry's formula (it lives in this repository), trust it, and install Gantry with
Homebrew's Java as a dependency. Homebrew only loads formulas from outside its official repositories once you trust
them; the command trusts the Gantry formula alone, not everything in the tap. Because the formula lives in this
repository rather than a `homebrew-*` one, Homebrew records that trust by the repository's URL. Check the install
with `gantry services`.

**Update**

```bash
brew update && brew upgrade gantry
```

**Uninstall**

```bash
brew uninstall gantry && brew untap sol-foundry/tap
brew autoremove          # optional: removes Homebrew's Java if nothing else needs it
```

`brew uninstall` also removes the trust the install command added.

### Your apps when uninstalling

Uninstalling (either way) keeps your apps' configuration in `~/.gantry/apps` and their containers and data in
Docker, so reinstalling picks them up again. To remove an app completely, run `gantry destroy <app>` for it
**before** uninstalling; then delete `~/.gantry` if you want nothing left.

## Quick start

```bash
gantry create my-app --services postgres valkey elasticmq   # database, cache and SQS-compatible queue
gantry create billing --preset fullstack                     # or start from a preset
gantry add my-app --service temporal                         # add a service later

gantry start my-app
gantry env my-app                                            # paste into the app's env file
gantry dashboard                                             # http://localhost:7070
```

## Dashboard

`gantry dashboard` serves the UI on `127.0.0.1:7070` (change with `--port`). The sidebar lists your **apps**
(each expands to its services, with how many are running: databases and queues open their explorers, the
cache, Temporal and Kafka open their own UI in a new tab) and the
**services** in the catalog (what each one is and how it integrates: dashboard UI, dependencies, ports).

- **New app** — name the app, tick the services it needs (or pick a preset), optionally start it right away.
- **App page** — services on the left (status, ports, Browse/Open UI, logs, settings, remove, *Add service*),
  the env block on the right (copy it, or rename variables to what your code reads). Start/Stop/Destroy on top;
  Start is disabled while every service runs. In a running app, adding, reconfiguring or removing a service in
  the dashboard applies at once (the CLI's `gantry add`/`remove` still wait for `gantry start`).
- **Database pages** — the breadcrumb shows the database name; **Sync from source** sits at the top right.
- Light, dark or system theme from the sidebar footer.

Per service:

| Service | Dashboard |
|---|---|
| postgres, mysql | built-in database browser (below), Flyway migrations, SQL console |
| valkey | RedisInsight, started with the cache and pre-connected to it; **Open UI** on the app page |
| elasticmq | built-in queue explorer: depth, peek, send, purge (see note below) |
| temporal | links to the Temporal Web UI |
| kafka | Kafka UI (kafbat), started with the broker and pre-connected to it; **Open UI** on the app page |
| gateway | built-in page: live call log, `gateway.yml` form (or YAML) editor, custom authorizers |

The database browser works at two levels:

- **Database:** tables and views with row estimates and sizes; create, rename, truncate and drop tables; create
  and drop schemas (Postgres).
- **Table:** page, sort and filter rows (`WHERE` clause); insert, edit (double-click a cell) and delete rows by
  primary key; add, edit and drop columns; create and drop indexes; view constraints.

Query results show at most 500 rows and 8 MB of text; longer values are cut at 64 KB, marked, and can't be
edited inline (use the SQL console for those). Every schema change shows its SQL first. **Run now** applies it directly; **Save as migration** writes it as the
next Flyway file (`V<n>__<description>.sql`) so the change is versioned with your app instead.

SQS has no true peek: reading a message counts as a receive. On a queue with a dead-letter queue (all declared
queues have one) the explorer therefore only peeks when you click **Peek messages**, and shows each message's
receive count, since a message moves to the DLQ after `max_receive_count` receives, peeks included.
Dead-letter queues aren't listed on their own: **Dead letters**, next to **Peek messages** on the source queue,
opens one in a dialog, and the list shows how many messages each queue has dead-lettered. The dialog peeks
(without spending receives) and purges. **Redrive**, in the dialog and next to **Dead letters**, moves every dead
letter back to the source queue with its attributes and a fresh receive count.

The API only answers requests whose `Host` is localhost and rejects cross-site browser requests, since it
can run SQL and manage containers.

## Services

| Name | What it runs | Options (`--set key=value`) |
|---|---|---|
| `postgres` | PostgreSQL | `version`, `name`, `user`, `password` (generated), `migrations`, `migrate_on_up` |
| `valkey` | Valkey, Redis-compatible, plus RedisInsight as its UI | `version`, `maxmemory`, `persist` |
| `elasticmq` | SQS-compatible queue | `queues`, `max_receive_count` |
| `temporal` | Temporal dev server | `version`, `search_attributes` |
| `kafka` | Apache Kafka (single KRaft broker), plus Kafka UI | `version`, `topics`, `partitions` |
| `mysql` | MySQL | `version`, `name`, `user`, `password`, `root_password` (generated), `migrations`, `migrate_on_up` |
| `gateway` | REST API gateway (Apache APISIX) with login checks; see [REST API gateway](#rest-api-gateway) | `version`, `upstream` |
| `websocket` | WebSocket gateway: a pub/sub relay standing in for an AWS API Gateway WebSocket API | `version` (Node) |

`gantry services` prints the same list with defaults.

- `queues` — `name[:visibilitySecs[:dlqName]],...`; each queue gets a dead-letter queue (default `<name>-dlq`).
- `search_attributes` — `Name=Type,...` registered on the Temporal dev server.
- `websocket` — browsers connect to `WEBSOCKET_WS_URL` (`/ws`) and send `{"action": "subscribe", "topic": "..."}`
  (or `unsubscribe`, or `topics: [...]`); the app POSTs `{"topic", "eventType", "payload"}` to `WEBSOCKET_URL/publish`
  and every subscriber of the topic receives it. `POST /subscribe` / `/unsubscribe` register topics for every open
  connection, since locally there are no connection ids. Nothing persists across restarts. Behind the `gateway`,
  route `/ws` and the HTTP paths to it as `auth: public`.
- `topics` — `name[:partitions],...` created on every `start` (existing topics are left alone). Other topics are
  auto-created on first use with `partitions` partitions (default 1). Host clients connect to
  `KAFKA_BOOTSTRAP_SERVERS`; other containers in the app use `kafka:19092`.
- Postgres and MySQL passwords are generated (24 random characters) when the database is added, unless you set
  one; `gantry env` shows them. Apps whose database was added before this keep the old default (the app name).
- Postgres and MySQL `name`, `user` and `password` are applied only when the data volume is first created.
  Changing them later makes connections fail; revert the option, change the role with SQL, or `gantry destroy`.
- `gantry remove` keeps the service's host ports reserved for the app (freed by `gantry destroy`), so adding it back
  gives it the same ports and settings written for them keep working.
- `gantry remove` keeps a database's data volume. Adding that database back asks what to do with it:
  `--fresh-data` deletes it so the database starts empty with its new credentials, `--keep-data` reuses it (set
  `user` and `password` to the old ones). The dashboard's **Add service** shows the same choice.
- Seed files: put `*.sql` / `*.sh` in `~/.gantry/apps/<app>/initdb/` (Postgres) or `initdb-mysql/` (MySQL). They
  run on a fresh volume only. For schema that evolves, prefer migrations.

## Database migrations (Flyway)

Each Postgres and MySQL service reads versioned [Flyway](https://documentation.red-gate.com/flyway) migrations
from its `migrations` directory: by default `~/.gantry/apps/<app>/migrations/<service>`. Point it at the folder
your service already ships, so local databases run exactly the migrations production will:

```bash
gantry add my-app --service postgres --set migrations=~/code/my-app/src/main/resources/db/migration --set migrate_on_up=true
gantry migrate my-app                                       # status: current version, pending, history
gantry migrate my-app migrate                               # apply pending migrations
gantry migrate my-app validate | repair | baseline | clean  # clean asks first; -y skips
gantry migrate inventory migrate --service mysql            # when an app has both databases
```

Paths are stored absolute (`~` and relative paths are resolved when set). With `migrate_on_up=true`, `gantry start`
and the dashboard's **Start** apply pending migrations after the containers are healthy. The dashboard's
**Migrations** tab shows the history, runs the same actions, opens each script, and creates new migration files.

If seed files already created objects, the first `migrate` records a version-0 baseline and then applies every
migration from V1, rather than refusing the non-empty schema.

## Data persistence

| Data | Survives `stop` / restart / reboot | Removed by |
|---|---|---|
| Postgres, MySQL | yes (Docker volume) | `gantry destroy`, `docker volume rm/prune` |
| Valkey | only with `persist=true` (Docker volume, append-only file) | `gantry destroy`, `docker volume rm/prune` |
| Queue messages | no (in-memory); declared queues are recreated | — |
| Kafka messages | no (kept in the container, removed by `stop`); declared topics are recreated | — |
| Temporal workflows | no (dev server is in-memory); search attributes are re-registered | — |

Turn Valkey persistence on with `gantry add my-app --service valkey --set persist=true`, or from the dashboard via the
service's **Settings**; changes apply at once if the app is running, else on the next start. Turning it off keeps the volume, so turning it on
again brings the old data back until the app is destroyed.

## Generated env

Only services the app has contribute keys:

| Service | Keys |
|---|---|
| postgres | `DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD DATABASE_URL` |
| mysql | `MYSQL_HOST MYSQL_PORT MYSQL_DATABASE MYSQL_USER MYSQL_PASSWORD MYSQL_URL` |
| valkey | `CACHE_HOST CACHE_PORT CACHE_ENDPOINT CACHE_URL REDIS_URL` (`CACHE_ENDPOINT` is `host:port`) |
| elasticmq | `AWS_ENDPOINT_URL_SQS` |
| temporal | `TEMPORAL_ADDRESS TEMPORAL_NAMESPACE` |
| kafka | `KAFKA_BOOTSTRAP_SERVERS` |
| gateway | `GATEWAY_URL` |

Credentials are local-only defaults. Services bind to `127.0.0.1`.

Values with spaces or special characters are single-quoted, which Node's `--env-file`, Docker Compose and
python-dotenv all read literally (no `$` expansion). Credentials inside `DATABASE_URL` are percent-encoded, so a
password like `p@ss/w#rd` still yields a valid URL while `DB_PASSWORD` keeps the raw value. Postgres and MySQL use
separate keys, so an app can have both; rename either set with `gantry envmap`.

### Use your own variable names

Map any default key to the name your code reads. Mappings are stored with the app and applied everywhere
the env is produced (`gantry env`, the generated file, the dashboard).

```bash
gantry envmap my-app DB_HOST=POSTGRES_HOST DATABASE_URL=SPRING_DATASOURCE_URL
gantry envmap my-app --omit REDIS_URL      # drop a variable you don't use
gantry envmap my-app --reset DB_HOST       # back to the default name
gantry envmap my-app                       # show the current mapping
```

In the dashboard, edit the **Your name** column of the Environment panel and save.

## Share an app

Export an app's configuration to a file a teammate can import, so everyone runs the same stack.

```bash
gantry export my-app -o my-app.gantry.json
gantry import my-app.gantry.json                         # shows what will happen, then asks
gantry import my-app.gantry.json --as my-app-copy --set postgres.version=16 -y
```

The file holds the services, their options, the env-variable mapping and each database's sync source. It never
holds **passwords** (secret options are generated on import) or **ports** (allocated per laptop). In the
dashboard, **Import app** in the sidebar opens the file and shows a preview where every field is editable,
including the app name; **Export** is on the app page.

- Renaming: `--as NAME` on the CLI, or the name field in the preview.
- Importing over an existing app replaces its configuration but keeps its local passwords and data volumes.
  A saved sync password is kept only while the file's source has the same host, port and database.
- Migration folders are exported as paths relative to the repository, so they resolve in your checkout. If
  Gantry can't find the checkout it asks where it is (`--repo NAME=PATH` on the CLI).
- CLI flags: `--as NAME`, `--set service.option=value` and `--source service.field=value` (host, port,
  database, user) to override the file, `--repo NAME=PATH`, and `-y` to skip the confirmation.

## Sync from a source database

Replace a local Postgres or MySQL database with a full copy of a source database, usually stage. The copy
**replaces all local data** and asks for confirmation first.

```bash
gantry sync my-app                                                 # asks for host, port, database, user and password the first time
gantry sync inventory --service mysql -y
gantry sync my-app --source host=stage2.internal --source user=me  # change the saved source
gantry sync my-app --table users --table config.flags              # copy only these tables
```

**Single tables.** `--table` (repeatable; `table`, or `schema.table` on Postgres) copies only those tables and leaves
every other table alone. In the dashboard, each table's page has its own **Sync from source** button.

- Each table is replaced in one transaction: if the copy fails (say a column differs between source and local), that
  table's local rows stay as they were, so there's never a half-copied state.
- Foreign-key checks are off while a table loads, so tables that reference it keep their rows, even ones pointing at
  rows the source doesn't have.
- The local table keeps its structure (from your migrations); only its rows are replaced. A table that doesn't exist
  locally is created from the source's definition.

In the dashboard, **Sync from source** on the database page opens the source settings and runs the sync, with
progress. A dot on the button means the source is complete and its password saved (hover for the source); while a
sync runs, the button shows a spinner and reopens the progress.

- The source's host, port, database and user are saved with the app and included in exports. The password is
  saved only on this laptop, in `~/.gantry/secrets.json` (owner-only permissions), and is never exported. The
  sync dialog fills it in, and the eye button reveals it.
- A sync saves the source and password only once it succeeds, so a mistyped source is never kept. The
  dashboard's **Save** stores the source, and the password if one is typed, without syncing or checking them. `gantry sync --source field=value` (host, port,
  database, user; repeatable) changes the source for that sync.
- The saved password belongs to one database: changing the source's host, port or database (in the dashboard,
  with `--source`, or by importing) drops it, and you are asked for it again. Changing only the user keeps it.
- If a sync is cut off mid-copy (the dashboard or CLI is stopped or killed), the local database is left
  incomplete. The next visit to the database page, and the next `gantry sync`, say so; sync again or
  `gantry destroy` the app.
- Reaching the source is up to you: connect the VPN or open an SSH tunnel first. A tunnel works with host
  `localhost`.
- A source with a newer major version than the local database is refused, with a hint to raise the local
  `version` option.

## REST API gateway

Put a REST API gateway in front of your app, so it gets the same login checks and identity headers locally as it does
behind your production gateway. The `gateway` service runs [Apache APISIX](https://apisix.apache.org) plus
gantry-auth, which verifies Firebase, Cognito, Supabase and other JWT logins, or asks an authorizer you write.

```bash
gantry add my-app --service gateway --set upstream=http://localhost:8080   # your app's base URL, no path
gantry start my-app
curl localhost:8090/health                                                 # GATEWAY_URL, proxied to the upstream
```

Out of the box the gateway is a **transparent proxy**: every request goes to `upstream` and nobody is asked to log
in. `localhost` in any URL means your machine (it is reached through the Docker host). gantry-auth runs from
Gantry's own jars, so use an installed Gantry (the script, Homebrew, or `./gradlew installDist`).

### gateway.yml

Routes and auth live in the app's `gateway.yml` (`~/.gantry/apps/<app>/gateway.yml`, written with a commented
example when the gateway is added). A running gateway picks up a saved change without a restart.

```bash
gantry gateway config my-app                        # print it
gantry gateway config my-app --file gateway.yml     # check a file and save it; problems are listed and nothing is saved
gantry gateway reload my-app                        # after editing ~/.gantry/apps/my-app/gateway.yml by hand: check it and apply it
```

A hand edit of the file takes effect only once it is applied, with `gantry gateway reload` (or `config --file`):
until then the gateway keeps running the config it last applied, and a file with problems is never applied.

In the dashboard, the **REST API Gateway** page's **Config** tab shows the same file as a form: routes, logins, user
lookups and headers as plain fields, with a filter for long route lists. **Edit as YAML** opens the text instead.
Saving from the form rewrites the file, so its comments are dropped. An example using most of it:

```yaml
upstream: http://localhost:8080          # optional; overrides the service's `upstream` option

authorizers:
  fb:
    type: firebase
    projectId: my-firebase-project
    onUnverifiedEmail: continue:email_not_verified

steps:
  user:
    type: http-lookup
    lookup:
      url: "{{upstream}}/internal/users/by-uid/{{uid}}"
      headers:
        x-internal-api-key: "{{secret:INTERNAL_API_KEY}}"
    create:                              # called when the lookup answers 404
      method: POST
      url: "{{upstream}}/internal/users"
      headers:
        x-internal-api-key: "{{secret:INTERNAL_API_KEY}}"
      body:
        uid: "{{uid}}"
        email: "{{email}}"
    map:
      userId: "$.id"                     # a JSON path in the response
    cacheSeconds: 300
    onFailure: continue:user_lookup_failed

routes:                                  # the first match wins
  - name: portal
    match:
      path: /portal/**                   # * is one path segment, ** anything; /portal/** also matches /portal
      methods: [GET, POST]               # optional, as is host
    auth: fb
    steps: [user]
    rewrite:
      stripPrefix: /portal               # or addPrefix: /api, or replace: /fixed/path
    headers:                             # set on the request your app receives
      x-user-id: "{{userId}}"
      x-gateway-user-email: "{{email}}"
      x-error-code: "{{errorCode}}"
  - name: health
    match:
      path: /actuator/**
    auth: public
  - name: admin
    match:
      path: /admin/**
    auth: deny

default:                                 # requests no route matches
  auth: public

cors:
  origins: [http://localhost:3000]
  credentials: true                      # needs origins listed explicitly, never *
  allowHeaders: [authorization, content-type]
  exposeHeaders: [x-request-id]
  maxAge: 600

log:
  maxCalls: 1000                         # calls kept in the call log
  maxBodyBytes: 65536                    # bodies are cut here when shown
  redact: [authorization, cookie, set-cookie, x-internal-api-key]
```

- **Routes.** A route `name` uses letters, digits, `.`, `_` and `-` (at most 64); `path` starts with `/`; `host` is a
  host name, optionally starting with `*.`. `auth` is `public`, `deny`, or the name of an authorizer. `upstream` on a route sends it elsewhere;
  `default` takes `auth`, `steps`, `headers` and `upstream` too. Every route streams responses (SSE) unbuffered and
  passes WebSocket upgrades through; on a protected route the authorizer checks the handshake (browsers can't set
  headers on one, so use `tokenFrom: query:<name>` there).
- **Authorizers.** Every type takes `tokenFrom` (`header:Authorization`, the default, or `query:<name>`),
  `onUnverifiedEmail` (`deny` or `continue:<code>`), `denyStatus` (default 403) and `missingTokenStatus` (both
  400-599).

  | `type` | Settings |
  |---|---|
  | `firebase` | `projectId` |
  | `cognito` | `region`, `userPoolId`, `clientIds`, `tokenUse` (`id`, the default, or `access`) |
  | `supabase` | `url` (the project URL; tokens are checked against its JWKS), or add `jwtSecret: "{{secret:NAME}}"` for a legacy HS256 project |
  | `jwt` | `jwksUrl`, or `jwks` (an inline key set), plus optional `issuer`, `audiences`, `algorithms` (default `[RS256]`) |
  | `custom` | `container` (an authorizer you pushed, below) or `url`, and `env` for its environment |

- **Templates.** Header values and step settings can use `{{name}}`: any claim of the verified token (`sub`,
  `email`, `email_verified`, ...), `uid` (the `user_id` claim, else `sub`), the keys a step `map`s, `errorCode`,
  `{{upstream}}` and `{{secret:NAME}}`. Values put into a step's URL are percent-encoded.
- **Soft failures.** `continue:<code>` (for `onUnverifiedEmail` and a step's `onFailure`) lets the request through
  without what failed and sets `{{errorCode}}` to `<code>`, so the app can decide; send it on as a header. This
  includes a step that can't be reached or times out. `deny` refuses the request: with 403 when the step answers
  with an error or without a mapped value, and with 503 (`step <name> is unavailable`) when it can't be reached or
  times out. An authorizer that errors or times out, or a secret that is not set, refuses it with 503 and a message
  saying why; the gateway never lets a request through by accident.
- **Headers.** The identity headers are `x-user-id`, `x-gateway-user-email`, `x-gateway-auth-strategy`,
  `x-internal-api-key` and `x-error-code` (change the list with `stripHeaders`). On a route that needs a login they
  always pass on, plus every shared or route header, with the values the authorizer gives (gantry-auth fills them
  from the header templates; an identity header no template sets arrives blank). On a `public` route the shared and
  route headers are set too, but a templated value arrives blank, since there is no caller to fill it from. Callers
  can never supply any of these themselves: the gateway removes the client's copies from every request.
- **Shared headers.** Headers every route sends can be written once, at the top level; a route's (or `default`'s)
  own `headers` add to them or replace one by name, in any case. They work exactly as if each route listed them:

  ```yaml
  headers:                               # for every route and the default
    x-user-id: "{{uid}}"
    x-gateway-auth-strategy: firebase
  routes:
    - name: portal
      match:
        path: /portal/**
      auth: fb
      headers:
        x-user-id: "{{userId}}"          # replaces the shared one on this route
  ```

### Secrets

Values such as API keys are referenced as `{{secret:NAME}}` and never written into `gateway.yml` or an export.
Set them per app; the value is read from standard input, never the command line:

```bash
gantry secret set my-app INTERNAL_API_KEY          # prompts without echo
printf %s "$KEY" | gantry secret set my-app INTERNAL_API_KEY
gantry secret list my-app                          # names only, and which ones gateway.yml uses but are not set
gantry secret remove my-app INTERNAL_API_KEY
```

Secrets are kept in `~/.gantry/secrets.json` (owner-only), next to saved sync passwords. A running gateway picks up a
new value at once, and a running custom authorizer that uses it is restarted. Its routes go back to it only once it runs
with the new env: until then (or if the restart fails) they answer 503, and `gantry gateway reload my-app` retries.

### Custom authorizers

When none of the built-in types fits, write the check yourself, in any language that runs in a container:

```bash
gantry authorizer init check --lang node            # or python, go; writes ./check (--dir PATH to choose)
gantry authorizer push my-app check ./check         # builds it and runs it next to the gateway; push again after changes
gantry authorizer list my-app
gantry authorizer remove my-app check               # stops and forgets it; the image is kept
```

Then use it in `gateway.yml`:

```yaml
authorizers:
  mine:
    type: custom
    container: check
    env:
      API_KEY: "{{secret:API_KEY}}"      # until it is set, the route answers 503 naming it
routes:
  - name: api
    match:
      path: /api/**
    auth: mine
    headers:
      x-user-id: ""                      # copied from the authorizer's answer
```

The contract: for each request on the route, the gateway sends the authorizer a `POST` with the caller's
`Authorization`, `Cookie`, `X-Request-Id`, `X-Forwarded-Host` and `X-User-Timezone` headers, plus
`X-Forwarded-Method` and `X-Forwarded-Uri` (the original method, and path with query). The authorizer listens on
`$PORT` (8080). A **2xx** answer lets the request through, and its identity headers (the `stripHeaders`) plus the
headers the route, or the top-level `headers`, names are copied onto it. **Anything else** goes back to the caller as it is.
If the authorizer can't be reached, the request is refused with 503. Each template's README has the details and a
way to try it on its own.

### Call log

Every call through the gateway is logged with its request and response (headers and bodies) and the auth decision.

- In the dashboard, the **REST API Gateway** page's **Calls** tab lists them live, filters by status and path, and opens
  each call; **Clear** empties the log. The **Authorizers** tab lists pushed authorizers.
- `gantry gateway calls my-app [--limit N] [--status 4xx|404] [--path /api/**] [-f]` prints them as a table;
  `-f` keeps printing new ones. `gantry gateway clear my-app` empties the log; new calls keep being recorded.
- Coding agents connected with `gantry mcp install` get a second tool, `get_gateway_calls`, with the same filters,
  so they can see what your app was actually sent and answered.

Headers listed under `log.redact` show as `[redacted]`, and so do, without being listed, headers a route sets from a
`{{secret:NAME}}` and the query parameter an authorizer reads its token from (`tokenFrom: query:<name>`). Bodies are
cut at `log.maxBodyBytes`. The files in the app's `gateway-log/` directory keep the full record, and only the newest
`log.maxCalls` calls are kept. On macOS that directory is yours alone; on Linux the gateway's containers run as other
users and must write there, so other local users can read the call log files (not list or replace them).

### Sharing

`gantry export` includes `gateway.yml` and where each custom authorizer's code lives (relative to its repository),
never secret values. `gantry import` writes `gateway.yml`, then lists the secrets to set and the authorizers to
push on this machine, each with the command to run.

## Connect coding agents

Let Claude Code, Cursor and Codex find an app's local infrastructure themselves, instead of pasting env values into
the chat. Run this inside the service's repository:

```bash
gantry mcp install my-app        # connect this repo's agents to the app 'my-app'
gantry mcp status                # which apps this repo's agents are connected to
gantry mcp uninstall my-app      # remove exactly what install added
```

Each agent gets an MCP server (`gantry mcp serve my-app`, started by the agent) with one read-only tool,
`get_connection_details`. It returns every service's state, host, ports and env variables (under your `envmap`
names), the queues with their dead-letter queues, and Kafka topics, read live each time, so ports, new services and
renamed variables are always current. The agent then connects with its own tools (`psql`, `aws`, `redis-cli`, ...).
Sync sources (stage credentials) are never shared.

The setup is personal to your machine and is never committed:

| Agent | Where the entry goes |
|---|---|
| Claude Code | its local scope (`~/.claude.json`, under this project), via the `claude` CLI; never the shared `.mcp.json` |
| Cursor | `.cursor/mcp.json`, hidden through `.git/info/exclude` |
| Codex | `.codex/config.toml`, hidden the same way; Codex reads it only for projects you've trusted |

- The entry holds no passwords or ports: only the path to this Gantry install, the Java it runs on (`JAVA_HOME`),
  `GANTRY_HOME` if set, and where `docker` is (`GANTRY_DOCKER`, plus `DOCKER_HOST` / `DOCKER_CONTEXT` if set), so it
  works for agents started from a GUI without your shell's `PATH`. Upgrades often move these; `gantry mcp status`
  flags an entry whose launcher, Java or docker is gone, and re-running install fixes it.
- On Windows the entry runs the `gantry.bat` launcher through `cmd /c`, and Claude Code needs its `claude` CLI on
  `PATH` (Gantry won't edit `~/.claude.json` by hand there).
- A config file the team has committed is never edited, and neither is one that already shows in `git status` (it
  may be on its way into the repo): that agent is skipped with a note. Other servers in the same file are kept,
  with the file's indentation; Codex's file is edited as text, so the rest of it stays exactly as written.
- Install never writes Cursor's or Codex's global config: run from your home directory, those agents are skipped.
- `--agent claude|cursor|codex` (repeatable) limits it to some agents. `--dir PATH` sets the project directory
  (default: the git repository you're in), e.g. for a service inside a monorepo.
- Restart the agent session after installing.
- Installed with Homebrew or the script, the entry points at their stable `gantry` command, so updates don't break it.

## Commands

```
gantry services | list
gantry create <app> [--services SERVICE...] [--preset NAME]
gantry add <app> --service SERVICE [--set k=v ...] [--fresh-data|--keep-data]   re-run to change options
gantry remove <app> <service>
gantry start | stop <app>                      stop keeps data
gantry logs <app> [service] [-f] [--tail N]
gantry env <app> [--write FILE]
gantry envmap <app> [KEY=NAME ...] [--omit KEY] [--reset KEY]
gantry migrate <app> [info|migrate|validate|repair|baseline|clean] [--service postgres|mysql] [-y]
gantry export <app> [-o FILE]
gantry import <file> [--as NAME] [--set service.option=v ...] [--source service.field=v ...] [--repo NAME=PATH] [-y]
gantry sync <app> [--service postgres|mysql] [--table T ...] [--source field=v ...] [-y]   replaces local data (or just tables T) with the source's
gantry destroy <app> [-y]                      deletes data volumes
gantry gateway calls <app> [--limit N] [--status 4xx|404] [--path GLOB] [-f]
gantry gateway clear <app>                     empties the call log
gantry gateway config <app> [--file PATH]      prints gateway.yml, or checks and saves a file
gantry authorizer init <name> --lang node|python|go [--dir PATH]
gantry authorizer push <app> <name> <dir>
gantry authorizer list <app> | remove <app> <name>
gantry secret set <app> <name>                 value from stdin
gantry secret list <app> | remove <app> <name>
gantry mcp install|uninstall <app> [--agent claude|cursor|codex ...] [--dir PATH]
gantry mcp status [--dir PATH]
gantry dashboard [--port 7070]
```

`start` regenerates the app's files first (so a newer Gantry's service definitions reach existing apps) and applies
every change made with `add`/`remove`: containers whose options or generated config files (e.g. the
queue list) changed are recreated, and services removed from the app are stopped. `destroy` deletes all of the
app's volumes, including those of services removed earlier.

State lives in `~/.gantry` (override with `GANTRY_HOME`): one directory per app holding the generated
`compose.yml`, config files and env file, plus `ports.json`, the host-port registry.

The launcher caps Java at a 512 MB heap and returns freed memory to the OS, so the dashboard idles around 100 MB.
Override with `JAVA_OPTS` (e.g. `JAVA_OPTS=-Xmx1g gantry dashboard`).

## Contributing

Building from source, running the tests, releasing, and adding a service or dashboard feature are covered in
[CONTRIBUTING.md](CONTRIBUTING.md).

## License

Apache License 2.0 — free to use, modify and redistribute, including commercially, provided the license and
the [NOTICE](NOTICE) attribution are kept. See [LICENSE](LICENSE).
