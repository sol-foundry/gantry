import { useEffect, useState } from "react";
import { PencilIcon } from "lucide-react";
import { toast } from "sonner";
import { api } from "@/api";
import { CopyButton, ErrorAlert } from "@/components/common";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { useAction, useLoad } from "@/hooks";
import { ServiceIcon, serviceLabel } from "@/lib/services";
import { cn } from "@/lib/utils";
import type { EnvEntry } from "@/types";

interface Draft {
  name: string;
  omitted: boolean;
}

function groupByService(entries: EnvEntry[]): [string, EnvEntry[]][] {
  const groups = new Map<string, EnvEntry[]>();
  entries.forEach((e) => groups.set(e.service, [...(groups.get(e.service) ?? []), e]));
  return [...groups.entries()];
}

/**
 * The generated env block. It reads like the .env file by default; renaming (to the names the app's code
 * reads) is a separate mode so the column stays compact next to the services.
 */
export function EnvPanel({ app, refreshKey, className }: { app: string; refreshKey: number; className?: string }) {
  const env = useLoad(() => api.env(app), [app, refreshKey]);
  const [editing, setEditing] = useState(false);
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const { busy, error, run, clearError } = useAction();
  const entries = env.data?.entries ?? [];

  const resetDrafts = () =>
    setDrafts(Object.fromEntries(entries.map((e) => [e.key, { name: e.omitted ? e.key : e.name, omitted: e.omitted }])));
  useEffect(resetDrafts, [env.data]); // eslint-disable-line react-hooks/exhaustive-deps

  const update = (key: string, patch: Partial<Draft>) => setDrafts((d) => ({ ...d, [key]: { ...d[key]!, ...patch } }));
  const dirty = entries.some((e) => {
    const d = drafts[e.key];
    return d && (d.omitted !== e.omitted || (!d.omitted && d.name !== e.name));
  });

  const cancel = () => {
    resetDrafts();
    clearError();
    setEditing(false);
  };
  const save = async () => {
    const names = Object.fromEntries(entries.map((e) => [e.key, drafts[e.key]!.omitted ? "" : drafts[e.key]!.name.trim()]));
    if (await run("save", () => api.setEnvNames(app, names))) {
      toast.success("Saved variable names");
      setEditing(false);
      env.reload();
    }
  };

  return (
    <Card size="sm" className={className}>
      <CardHeader>
        <CardTitle>Environment</CardTitle>
        <CardDescription>
          Paste into the app's env file; also written to{" "}
          <span className="font-mono break-all">
            ~/.gantry/apps/{app}/{app}.env
          </span>
          .
        </CardDescription>
        <CardAction className="flex gap-1">
          {entries.length > 0 && !editing && (
            <Button variant="ghost" size="icon-sm" onClick={() => setEditing(true)} aria-label="Rename variables" title="Rename variables">
              <PencilIcon />
            </Button>
          )}
          {env.data && <CopyButton text={env.data.text} />}
        </CardAction>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <ErrorAlert message={env.error} />
        <ErrorAlert message={error} onDismiss={clearError} />
        {env.data === undefined && !env.error && <Skeleton className="h-40 w-full" />}
        {env.data && entries.length === 0 && <p className="text-sm text-muted-foreground">No variables yet. Add a service to get some.</p>}
        {editing && (
          <p className="text-xs text-muted-foreground">
            Rename a variable to the name your code reads, or omit it. Mappings are saved with the app.
          </p>
        )}
        {groupByService(entries).map(([service, group]) => (
          <section key={service} className="flex flex-col gap-1">
            <h4 className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
              <ServiceIcon id={service} className="size-3.5" />
              {serviceLabel(service)}
            </h4>
            <ul className="divide-y rounded-lg border font-mono text-xs">
              {group.map((e) =>
                editing ? (
                  <EditRow key={e.key} entry={e} draft={drafts[e.key]} onChange={(patch) => update(e.key, patch)} />
                ) : (
                  <ViewRow key={e.key} entry={e} />
                ),
              )}
            </ul>
          </section>
        ))}
      </CardContent>
      {editing && (
        <CardFooter className="justify-end gap-2">
          <Button variant="outline" size="sm" disabled={!!busy} onClick={cancel}>
            Cancel
          </Button>
          <Button size="sm" disabled={!dirty || !!busy} onClick={save}>
            {busy ? "Saving…" : "Save names"}
          </Button>
        </CardFooter>
      )}
    </Card>
  );
}

function ViewRow({ entry }: { entry: EnvEntry }) {
  const renamed = !entry.omitted && entry.name !== entry.key;
  return (
    <li
      className={cn("flex items-baseline gap-2 px-2.5 py-1.5", entry.omitted && "text-muted-foreground line-through")}
      title={`${entry.key} = ${entry.value}`}
    >
      <span className="shrink-0">{entry.omitted ? entry.key : entry.name}</span>
      {renamed && <span className="shrink-0 text-[10px] text-muted-foreground">← {entry.key}</span>}
      <span className="min-w-0 flex-1 truncate text-right text-muted-foreground">{entry.omitted ? "omitted" : entry.value}</span>
    </li>
  );
}

function EditRow({ entry, draft, onChange }: { entry: EnvEntry; draft?: Draft; onChange: (patch: Partial<Draft>) => void }) {
  const omitted = draft?.omitted ?? false;
  return (
    <li className="flex flex-col gap-1 px-2.5 py-1.5">
      <div className="flex items-center justify-between gap-2 text-muted-foreground">
        <span className="truncate">{entry.key}</span>
        <label className="flex shrink-0 items-center gap-1.5 font-sans">
          <Checkbox checked={omitted} onCheckedChange={(checked) => onChange({ omitted: checked })} />
          Omit
        </label>
      </div>
      <Input
        aria-label={`Name for ${entry.key}`}
        className="h-7 font-mono text-xs md:text-xs"
        value={draft?.name ?? ""}
        disabled={omitted}
        spellCheck={false}
        onChange={(ev) => onChange({ name: ev.target.value })}
      />
    </li>
  );
}
