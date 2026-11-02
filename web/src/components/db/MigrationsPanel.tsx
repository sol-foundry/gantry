import { useState } from "react";
import {
  ArrowUpCircleIcon,
  CheckCircle2Icon,
  FileCodeIcon,
  FolderIcon,
  RefreshCwIcon,
  ShieldCheckIcon,
  TrashIcon,
  WrenchIcon,
  FlagIcon,
} from "lucide-react";
import { toast } from "sonner";
import { api } from "@/api";
import { CodeBlock, ConfirmButton, ErrorAlert } from "@/components/common";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Field, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import { useAction, useLoad } from "@/hooks";
import { href } from "@/lib/router";
import { cn } from "@/lib/utils";
import type { MigrationAction, MigrationRow, MigrationStatus, SqlService } from "@/types";

const SECONDARY_ACTIONS: { action: MigrationAction; label: string; icon: typeof RefreshCwIcon; hint: string }[] = [
  { action: "validate", label: "Validate", icon: ShieldCheckIcon, hint: "Check applied migrations still match the files" },
  { action: "repair", label: "Repair", icon: WrenchIcon, hint: "Remove failed entries and realign checksums" },
  { action: "baseline", label: "Baseline", icon: FlagIcon, hint: "Mark an existing schema as version 1" },
];

/** Dot colour per Flyway state; matched by prefix so "Above Target" and "Success (out of order)" etc. still land. */
const STATE_TONES: [string, string][] = [
  ["success", "bg-emerald-500"],
  ["pending", "bg-sky-500"],
  ["failed", "bg-destructive"],
  ["missing", "bg-destructive"],
  ["future", "bg-amber-500"],
  ["outdated", "bg-amber-500"],
  ["ignored", "bg-amber-500"],
];

function StateBadge({ state }: { state: string }) {
  const normalized = state.toLowerCase();
  const tone = STATE_TONES.find(([key]) => normalized.startsWith(key))?.[1] ?? "bg-muted-foreground/50";
  return (
    <Badge variant="outline" className="gap-1.5 font-normal">
      <span className={cn("size-1.5 rounded-full", tone)} />
      {state}
    </Badge>
  );
}

/** Flyway status and actions for one SQL service, plus authoring of new versioned migration files. */
export function MigrationsPanel({ app, service, onChanged }: { app: string; service: SqlService; onChanged?: () => void }) {
  const db = api.db(app, service);
  const loaded = useLoad(() => db.migrations(), [app, service]);
  // An action's outcome carries fresh status; prefer it until the next reload.
  const [override, setOverride] = useState<MigrationStatus>();
  const [script, setScript] = useState<string>();
  const { busy, error, run, clearError } = useAction();

  const status = override ?? loaded.data;

  const reload = () => {
    setOverride(undefined);
    loaded.reload();
  };

  const act = (action: MigrationAction) =>
    run(action, async () => {
      const outcome = await db.migrate(action);
      setOverride(outcome.status);
      toast.success(outcome.message);
      if (action !== "validate") onChanged?.();
    });

  return (
    <div className="flex flex-col gap-6">
      <Card className="shrink-0">
        <CardHeader>
          <CardTitle>Flyway migrations</CardTitle>
          <CardDescription>
            Read from the service&apos;s <code className="font-mono text-xs">migrations</code> option; change it in the service settings on
            the{" "}
            <a className="underline underline-offset-3 hover:text-foreground" href={href.app(app)}>
              app page
            </a>
            .
          </CardDescription>
          <CardAction>
            <Button variant="ghost" size="icon-sm" onClick={reload} aria-label="Refresh" title="Refresh">
              <RefreshCwIcon />
            </Button>
          </CardAction>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <ErrorAlert message={loaded.error} />
          {!status && !loaded.error && <Skeleton className="h-20 w-full" />}
          {status && (
            <>
              <div className="flex min-w-0 items-center gap-2 rounded-lg border bg-muted/40 px-3 py-2">
                <FolderIcon className="size-4 shrink-0 text-muted-foreground" />
                <code className="truncate font-mono text-xs" title={status.location}>
                  {status.location}
                </code>
                {!status.locationExists && (
                  <Badge variant="outline" className="ml-auto shrink-0 text-amber-600 dark:text-amber-400">
                    created on first migration
                  </Badge>
                )}
              </div>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                <Metric label="Current version" value={status.current ?? "none"} />
                <Metric label="Pending" value={String(status.pending)} highlight={status.pending > 0} />
                <Metric label="Applied" value={String(status.migrations.filter((m) => m.installedOn).length)} />
              </div>
            </>
          )}
          <ErrorAlert message={error} onDismiss={clearError} />
        </CardContent>
        {status && (
          <CardFooter className="flex-wrap gap-2">
            <Button disabled={!!busy || status.pending === 0} onClick={() => act("migrate")}>
              {busy === "migrate" ? <Spinner /> : status.pending === 0 ? <CheckCircle2Icon /> : <ArrowUpCircleIcon />}
              {status.pending === 0 ? "Up to date" : `Migrate (${status.pending})`}
            </Button>
            {SECONDARY_ACTIONS.map(({ action, label, icon: Icon, hint }) => (
              <Button key={action} variant="outline" title={hint} disabled={!!busy} onClick={() => act(action)}>
                {busy === action ? <Spinner /> : <Icon />}
                {label}
              </Button>
            ))}
            <ConfirmButton
              destructive
              className="ml-auto"
              disabled={!!busy}
              title={`Clean the ${service} database?`}
              description={`Flyway clean drops every object (tables, views, data and the migration history) in the ${service} database of ${app}.`}
              confirmLabel="Clean"
              onConfirm={() => act("clean")}
            >
              <TrashIcon />
              Clean
            </ConfirmButton>
          </CardFooter>
        )}
      </Card>

      {/* Status stays on top; history and the new-migration form share the rest side by side, each scrolling. */}
      <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,24rem)]">
        {status ? (
          <Card>
            <CardHeader>
              <CardTitle>History</CardTitle>
              <CardDescription>Click a migration to view its script.</CardDescription>
            </CardHeader>
            <CardContent>
              {status.migrations.length === 0 ? <NoMigrations /> : <MigrationTable rows={status.migrations} onOpen={setScript} />}
            </CardContent>
          </Card>
        ) : (
          <div />
        )}
        <NewMigration app={app} service={service} onCreated={reload} />
      </div>

      {script && <ScriptDialog app={app} service={service} name={script} onClose={() => setScript(undefined)} />}
    </div>
  );
}

function Metric({ label, value, highlight }: { label: string; value: string; highlight?: boolean }) {
  return (
    <div className="flex flex-col gap-0.5 rounded-lg border px-3 py-2">
      <span className="text-xs text-muted-foreground">{label}</span>
      <span className={cn("font-mono text-base font-semibold", highlight && "text-sky-600 dark:text-sky-400")}>{value}</span>
    </div>
  );
}

function MigrationTable({ rows, onOpen }: { rows: MigrationRow[]; onOpen: (script: string) => void }) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Version</TableHead>
          <TableHead>Description</TableHead>
          <TableHead>Type</TableHead>
          <TableHead>State</TableHead>
          <TableHead>Installed on</TableHead>
          <TableHead className="text-right">Time</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((m, i) => (
          <TableRow
            key={`${m.version ?? "R"}-${m.script}-${i}`}
            className={cn(m.script && "cursor-pointer")}
            title={m.script ? `View ${m.script}` : undefined}
            onClick={() => m.script && onOpen(m.script)}
          >
            <TableCell className="font-mono text-xs">{m.version ?? "R"}</TableCell>
            <TableCell>{m.description}</TableCell>
            <TableCell className="text-xs text-muted-foreground">{m.type}</TableCell>
            <TableCell>
              <StateBadge state={m.state} />
            </TableCell>
            <TableCell className="text-xs text-muted-foreground">
              {m.installedOn ? new Date(m.installedOn).toLocaleString() : "—"}
            </TableCell>
            <TableCell className="text-right text-xs text-muted-foreground tabular-nums">
              {m.executionMs != null ? `${m.executionMs} ms` : ""}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function NoMigrations() {
  return (
    <Empty className="border border-dashed">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <FileCodeIcon />
        </EmptyMedia>
        <EmptyTitle>No migrations yet</EmptyTitle>
        <EmptyDescription>
          Flyway applies <code>V1__create_users.sql</code>, <code>V2__add_email.sql</code>, … in version order and re-runs repeatable ones
          like <code>R__views.sql</code> when they change. Add one below, or point the <code>migrations</code> option at your service
          repo&apos;s folder (e.g. <code>src/main/resources/db/migration</code>).
        </EmptyDescription>
      </EmptyHeader>
    </Empty>
  );
}

function NewMigration({ app, service, onCreated }: { app: string; service: SqlService; onCreated: () => void }) {
  const [description, setDescription] = useState("");
  const [sql, setSql] = useState("");
  const { busy, error, run, clearError } = useAction();

  const create = () =>
    run("create", async () => {
      const file = await api.db(app, service).newMigration(description, sql);
      toast.success(`Created ${file.name}`, { description: "Apply it with Migrate." });
      setDescription("");
      setSql("");
      onCreated();
    });

  return (
    <Card>
      <CardHeader>
        <CardTitle>New migration</CardTitle>
        <CardDescription>Written to the migrations directory as the next version; apply it with Migrate.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <Field>
          <FieldLabel htmlFor="migration-description">Description</FieldLabel>
          <Input
            id="migration-description"
            value={description}
            placeholder="add email to users"
            onChange={(e) => setDescription(e.target.value)}
          />
        </Field>
        <Field>
          <FieldLabel htmlFor="migration-sql">SQL</FieldLabel>
          <Textarea
            id="migration-sql"
            className="min-h-32 font-mono text-xs md:text-xs"
            value={sql}
            spellCheck={false}
            placeholder="ALTER TABLE users ADD COLUMN email text;"
            onChange={(e) => setSql(e.target.value)}
          />
        </Field>
        <ErrorAlert message={error} onDismiss={clearError} />
      </CardContent>
      <CardFooter className="justify-end">
        <Button disabled={!!busy || !description.trim() || !sql.trim()} onClick={create}>
          {busy ? <Spinner /> : <FileCodeIcon />}
          Create file
        </Button>
      </CardFooter>
    </Card>
  );
}

function ScriptDialog({ app, service, name, onClose }: { app: string; service: SqlService; name: string; onClose: () => void }) {
  const script = useLoad(() => api.db(app, service).script(name), [app, service, name]);
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle className="font-mono">{name}</DialogTitle>
          <DialogDescription>Migration script</DialogDescription>
        </DialogHeader>
        <ErrorAlert message={script.error} />
        {script.loading && <Skeleton className="h-32 w-full" />}
        {script.data && <CodeBlock className="max-h-[60vh]">{script.data.sql}</CodeBlock>}
      </DialogContent>
    </Dialog>
  );
}
