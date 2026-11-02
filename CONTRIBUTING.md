# Contributing

This guide is for working on Gantry itself. To install and use it, see the [README](README.md).

## Build from source

You need a JDK 17+ and Node 20+ (for the dashboard). Gradle downloads a matching JDK if yours is missing.

```bash
./gradlew installDist                                 # -> build/install/gantry/bin/gantry (dashboard bundled)
alias gantry=$PWD/build/install/gantry/bin/gantry     # use this build as `gantry`
```

This is also the way to run Gantry on Windows (`build\install\gantry\bin\gantry.bat`). Use an installed build
rather than `./gradlew run` when trying `gantry mcp install`: agent entries point at the launcher.

## Develop

```bash
./gradlew build             # ktlint + tests + dashboard bundle
./gradlew ktlintFormat      # auto-fix style
./gradlew test -PskipWeb    # Kotlin tests only, skipping the npm build
cd web && npm run dev       # UI hot reload on :5173; run `gantry dashboard` alongside it
```

Under `npm run dev` the dashboard also shows the [Agentation](https://agentation.com) toolbar (bottom-right): click
elements to annotate them and copy the notes for a coding agent. It is a dev dependency and never ships in the
built dashboard.

## Branches

- `develop` is the default branch: work lands there, through pull requests or direct pushes.
- `main` is the stable branch releases are cut from. It's protected: it only changes through a pull request from
  `develop` (no approval needed) once CI passes, and can't be pushed to, force-pushed or deleted, by admins either.

## Releasing

Versions are `MAJOR.MINOR.PATCH`, with release candidates as `MAJOR.MINOR.PATCH-rcN`. The tag is the source of
truth: there's no version bump to commit.

1. Open a pull request from `develop` to `main`, wait for CI, and merge it.
2. Tag `main` and push the tag:

   ```bash
   git switch main && git pull
   git tag v1.2.0-rc1 && git push origin v1.2.0-rc1   # a release candidate
   git tag v1.2.0 && git push origin v1.2.0           # the final release
   ```

   Publishing a release in the GitHub UI (or `gh release create v1.2.0 --target main`) works the same way.

Either triggers the release workflow (`.github/workflows/release.yml`), which:

1. rejects tags that aren't `vMAJOR.MINOR.PATCH` or `vMAJOR.MINOR.PATCH-rcN`;
2. writes the version into `version.properties`, then builds and tests;
3. attaches `gantry-<version>.tar.gz` and its `.sha256` to the GitHub release, creating it if needed. A release
   candidate is marked as a pre-release; a final release as the latest;
4. for a final release only, commits `Formula/gantry.rb` (pointing at the new archive) and `version.properties`
   (the new version) to `develop`, the default branch `brew tap` reads the formula from, unless `develop` already
   points at a newer version (e.g. a patch to an older line). They reach `main` with the next merge.

So release candidates reach only people who ask for them (`GANTRY_VERSION=1.2.0-rc1` with `install.sh`);
`install.sh`'s latest and Homebrew get final releases. `install.sh` itself is always downloaded from `main`.

## Project layout

```
src/main/kotlin/dev/gantry/
  cli/        CLI (Clikt)
  engine/     catalog, port allocation, compose rendering, container lifecycle
  db/         SQL dialects (Postgres, MySQL), database explorer, Flyway migrations
  server/     dashboard JSON API (Ktor), queue explorer
  mcp/        agent access: stdio MCP server, connection details, per-agent config
src/main/resources/
  catalog/    one directory per service (service.json + services.yml)
  presets/    named service bundles
web/          React dashboard (Vite, TypeScript, Tailwind, shadcn/ui), bundled into the jar
```

## Adding a service

Create `src/main/resources/catalog/<id>/` containing:

- `service.json`
  - `id` (the name users type: `gantry add my-app --service <id>`), `description`
  - `options` — name → default (defaults may use placeholders, e.g. `"{{app_id}}"`)
  - `option_meta` — optional per-option hints: `{"type": "boolean" | "text" | "path", "help": "..."}`. Booleans are
    validated (`true`/`false`) and shown as a toggle in the dashboard; paths are stored absolute (`~` expanded).
    `"secret": true` marks a credential: it is never exported and is generated when the service is added or imported.
  - `ports` — name → default host port; the engine picks the next free one per app
  - `requires` — service ids that are added automatically
  - `volumes` — named volumes; emitted only when the rendered `services.yml` mounts them, so options can switch them off
  - `dirs` (created in the app dir), `files` (output name → template, rendered into the app dir)
  - `post_up` — compose services run (profile `init`) after `gantry start`
  - `env` — keys the app should receive, as templates
  - `ui` — one of `{"builtin": "<name>"}` (`postgres`, `mysql`, `queue`: the dashboard route),
    `{"external": "<url template>"}`, `{"via": "<other service>"}`
- `services.yml` — the compose fragment, indented under `services:`.

Placeholders: `{{app}}`, `{{app_id}}`, `{{dir}}`, `{{svc_dir}}`, `{{opt.x}}`, `{{port.x}}`. Append `|url` to
percent-encode a value inside a URL (e.g. `{{opt.password|url}}` in a connection string). Values substituted into
`services.yml` are YAML-escaped, so wrap them in double quotes.

Services with `files` get a `gantry.config-hash` label on the first service of their fragment, so `gantry start`
recreates the container when a generated file changes; don't declare your own `labels:` on that service. Multi-line computed blocks are written as
`{{@name}}` on their own line and implemented in `engine/Template.kt`.

Add a test in `EngineTest.kt` covering the rendered output. Tests build `Engine(..., dockerCommand = NO_DOCKER)`:
compose project names (`gantry-<app>`) are global on the Docker daemon, so a test calling `destroy("shop")` with
the real `docker` would delete a developer's real `shop` app.

A new SQL database also needs a `Dialect` in `db/Dialect.kt` (quoting, catalog queries, DDL differences) and its
JDBC driver and Flyway database module in `build.gradle.kts`; the explorer, row editing and migrations then work
unchanged. It must expose `name`, `user`, `password` and `migrations` options and a `main` port.

## Dashboard UI

`web/` is React + TypeScript with Tailwind v4 and [shadcn/ui](https://ui.shadcn.com) (style `base-nova` on Base UI,
from preset `b14BnWWbYP`; see `web/components.json`). Add components with `npx shadcn@latest add <name>` from
`web/`; they land in `src/components/ui/` and are ours to edit. Layout follows the `sidebar-08` block.

- Pages live in `src/pages/`, routed by hash in `src/lib/router.ts`; shared helpers (error alert, status badge,
  page header, confirm button) are in `src/components/common.tsx`.
- Style with Tailwind utilities and the theme's semantic tokens (`bg-muted`, `text-muted-foreground`, ...) so
  light and dark both work; no hand-written CSS files.
- Base UI components compose with `render={<a href=... />}` rather than `asChild`.
- The shell is pinned to the viewport: the main content panel (under the breadcrumb) is the one scroller, and
  cards inside it take their natural height.
- Scrolling uses `<ScrollArea>` (`src/components/ui/scroll-area.tsx`): an overlay bar with no gutter that shows only
  while scrolling or when pointed at. Pass `orientation="horizontal"` / `"both"` for wide content; `Table`,
  `CodeBlock` and result grids already do. Native scrollbars are hidden app-wide (`src/styles/scrollbars.css`),
  so don't add bare `overflow-auto` containers where users need to see a bar.

## Guidelines

- Keep comments for the *why*; names carry the *what*.
- Engine code has no HTTP or CLI knowledge; the server and CLI stay thin adapters over it.
- User-correctable failures return errors with an actionable message.
- A built-in explorer (like Postgres or queues) is only for services without a good UI of their own.
