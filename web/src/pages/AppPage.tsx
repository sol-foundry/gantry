import { useState } from "react";
import { BoxIcon, DownloadIcon, ExternalLinkIcon, PlayIcon, ScrollTextIcon, SettingsIcon, SquareIcon, Trash2Icon } from "lucide-react";
import { toast } from "sonner";
import { api } from "@/api";
import { AddServiceDialog } from "@/components/AddServiceDialog";
import { ConfirmButton, ErrorAlert, PageHeader, StatusBadge } from "@/components/common";
import { EnvPanel } from "@/components/EnvPanel";
import { LogsDialog } from "@/components/LogsDialog";
import { ServiceSettingsDialog } from "@/components/ServiceSettingsDialog";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { useLoad } from "@/hooks";
import { useGantry } from "@/lib/gantry";
import { href, navigate, type Tool } from "@/lib/router";
import { ServiceIcon, serviceLabel } from "@/lib/services";
import type { ServiceStatus } from "@/types";

const STATUS_POLL_MS = 4000;

type Lifecycle = "up" | "down" | "destroy" | "remove";

export function AppPage({ app }: { app: string }) {
  const status = useLoad(() => api.status(app), [app], STATUS_POLL_MS);
  const { catalog, reloadApps } = useGantry();
  const [busy, setBusy] = useState<Lifecycle>();
  const [logsFor, setLogsFor] = useState<string>();
  const [settingsFor, setSettingsFor] = useState<string>();
  const [envKey, setEnvKey] = useState(0);

  const services = status.data?.services ?? [];
  const running = services.filter((s) => s.state === "running").length;

  const changed = () => {
    status.reload();
    reloadApps();
    setEnvKey((k) => k + 1);
  };

  /** Runs a lifecycle action, reporting the outcome as a toast; returns whether it succeeded. */
  const act = async (label: Lifecycle, action: () => Promise<unknown>, success: string): Promise<boolean> => {
    setBusy(label);
    try {
      await action();
      toast.success(success);
      return true;
    } catch (e) {
      toast.error((e as Error).message);
      return false;
    } finally {
      setBusy(undefined);
      changed();
    }
  };

  const start = () =>
    act(
      "up",
      async () => {
        const { notes } = await api.up(app);
        notes.forEach((note) => toast.info(note));
      },
      `${app} is up`,
    );
  const stop = () => act("down", () => api.down(app), `${app} stopped; data is kept`);
  const destroy = async () => {
    if (await act("destroy", () => api.destroy(app), `Destroyed ${app}`)) navigate(href.home());
  };
  const remove = (service: string) =>
    act(
      "remove",
      async () => {
        const { notes } = await api.removeService(app, service);
        notes.forEach((note) => toast.info(note));
      },
      `Removed ${serviceLabel(service)}`,
    );
  // Start only has work to do while something is stopped; changes to a running app are applied as they're made.
  const allRunning = services.length > 0 && running === services.length;

  const settingsService = services.find((s) => s.id === settingsFor);
  const settingsCatalog = catalog?.services.find((c) => c.id === settingsFor);

  return (
    <>
      <PageHeader
        icon={<BoxIcon className="size-5" />}
        title={app}
        description={
          status.data ? `${running} of ${services.length} service${services.length === 1 ? "" : "s"} running` : "Loading status…"
        }
        actions={
          <>
            <Button variant="outline" nativeButton={false} render={<a href={api.exportUrl(app)} download />}>
              <DownloadIcon />
              Export
            </Button>
            <Button disabled={!!busy || services.length === 0 || allRunning} title={allRunning ? "Every service is running" : undefined} onClick={start}>
              {busy === "up" ? <Spinner /> : <PlayIcon />}
              {busy === "up" ? "Starting…" : "Start"}
            </Button>
            <Button variant="outline" disabled={!!busy || running === 0} onClick={stop}>
              {busy === "down" ? <Spinner /> : <SquareIcon />}
              Stop
            </Button>
            <ConfirmButton
              destructive
              disabled={!!busy}
              title={`Destroy ${app}?`}
              description="This removes its containers, its data volumes and its configuration. It cannot be undone."
              confirmLabel="Destroy"
              onConfirm={destroy}
            >
              <Trash2Icon />
              Destroy
            </ConfirmButton>
          </>
        }
      />
      <div className="flex flex-col gap-6">
        <ErrorAlert message={status.error} />
        {/* On lg+ both columns fill the remaining height; each card scrolls its own content. */}
        <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,26rem)]">
          <Card size="sm">
            <CardHeader>
              <CardTitle>Services</CardTitle>
              <CardDescription>Containers, ports and tools for each service. Changes apply at once while the app is running.</CardDescription>
              <CardAction>
                <AddServiceDialog app={app} catalog={catalog?.services ?? []} existing={services.map((s) => s.id)} onAdded={changed} />
              </CardAction>
            </CardHeader>
            <CardContent>
              {status.data === undefined && !status.error && (
                <div className="flex flex-col gap-2">
                  {[0, 1, 2].map((i) => (
                    <Skeleton key={i} className="h-20 w-full" />
                  ))}
                </div>
              )}
              {status.data && services.length === 0 && (
                <Empty className="border">
                  <EmptyHeader>
                    <EmptyMedia variant="icon">
                      <BoxIcon />
                    </EmptyMedia>
                    <EmptyTitle>No services yet</EmptyTitle>
                    <EmptyDescription>Add a database, cache, queue or workflow engine with “Add service”.</EmptyDescription>
                  </EmptyHeader>
                </Empty>
              )}
              {services.length > 0 && (
                <ul className="divide-y rounded-lg border">
                  {services.map((s) => (
                    <ServiceRow
                      key={s.id}
                      app={app}
                      service={s}
                      disabled={!!busy}
                      hasOptions={Object.keys(s.options).length > 0}
                      onLogs={() => setLogsFor(s.id)}
                      onSettings={() => setSettingsFor(s.id)}
                      onRemove={() => remove(s.id)}
                    />
                  ))}
                </ul>
              )}
            </CardContent>
          </Card>
          <EnvPanel app={app} refreshKey={envKey} />
        </div>
      </div>
      {settingsService && settingsCatalog && (
        <ServiceSettingsDialog
          app={app}
          service={settingsService}
          catalog={settingsCatalog}
          onClose={() => setSettingsFor(undefined)}
          onSaved={changed}
        />
      )}
      {logsFor && <LogsDialog app={app} service={logsFor} onClose={() => setLogsFor(undefined)} />}
    </>
  );
}

function ServiceRow(props: {
  app: string;
  service: ServiceStatus;
  disabled: boolean;
  hasOptions: boolean;
  onLogs: () => void;
  onSettings: () => void;
  onRemove: () => Promise<unknown>;
}) {
  const { app, service: s } = props;
  const version = s.options["version"];
  return (
    <li className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1.5 p-3">
      <div className="row-span-2 flex size-8 items-center justify-center rounded-md border bg-muted/40">
        <ServiceIcon id={s.id} className="size-4" />
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <span className="font-medium">{serviceLabel(s.id)}</span>
          <StatusBadge state={s.state} health={s.health} />
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <UiButton app={app} service={s} />
          <Button variant="ghost" size="sm" onClick={props.onLogs}>
            <ScrollTextIcon />
            Logs
          </Button>
          {props.hasOptions && (
            <Button variant="ghost" size="icon-sm" onClick={props.onSettings} aria-label="Settings" title="Settings">
              <SettingsIcon />
            </Button>
          )}
          <ConfirmButton
            variant="ghost"
            size="icon-sm"
            disabled={props.disabled}
            title={`Remove ${serviceLabel(s.id)} from ${app}?`}
            description="Its container stops on the next Start. Its data volume is kept until the app is destroyed."
            confirmLabel="Remove"
            destructive
            onConfirm={props.onRemove}
          >
            <Trash2Icon />
            <span className="sr-only">Remove</span>
          </ConfirmButton>
        </div>
      </div>
      <div className="flex min-w-0 flex-col gap-1 text-xs text-muted-foreground">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 font-mono">
          {Object.entries(s.ports).map(([name, port]) => (
            <span key={name}>
              {name} <span className="text-foreground">:{port}</span>
            </span>
          ))}
          {version && <span>v{version}</span>}
        </div>
        {/* The first sentence says what it is; the rest of the catalog description is on the service page. */}
        <p className="truncate" title={s.description}>
          {s.description.split(". ")[0]}
        </p>
        {s.ui?.kind === "hint" && <p className="italic">{s.ui.note}</p>}
      </div>
    </li>
  );
}

/** Services with their own UI link out to it; the rest open the built-in explorer. */
function UiButton({ app, service }: { app: string; service: ServiceStatus }) {
  const ui = service.ui;
  if (ui?.kind === "external") {
    return (
      <Button variant="outline" size="sm" nativeButton={false} render={<a href={ui.url} target="_blank" rel="noreferrer" />}>
        <ExternalLinkIcon />
        Open UI
      </Button>
    );
  }
  if (ui?.kind === "builtin") {
    return (
      <Button size="sm" variant="secondary" nativeButton={false} render={<a href={href.tool(app, ui.name as Tool)} />}>
        Browse
      </Button>
    );
  }
  return null;
}
