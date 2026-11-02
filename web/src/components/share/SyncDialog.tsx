import { useEffect, useRef, useState } from "react";
import { EyeIcon, EyeOffIcon } from "lucide-react";
import { toast } from "sonner";
import { ApiError, api } from "@/api";
import { ErrorAlert } from "@/components/common";
import { SyncProgress } from "@/components/share/SyncProgress";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Field, FieldDescription, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { serviceLabel } from "@/lib/services";
import type { SourceSettings, SqlService, SyncJob } from "@/types";

type Step = "form" | "confirm" | "progress";
type FieldName = "host" | "port" | "database" | "user" | "password";

const EMPTY: SourceSettings = { host: "", port: 0, database: "", user: "" };

interface Props {
  app: string;
  service: SqlService;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Pre-fills the form; defaults to the source saved for the service. */
  initialSource?: SourceSettings;
  /** The local database's name if the caller already knows it; the dialog looks it up otherwise. */
  database?: string;
  /** Called when a sync has copied the data, or the dialog closes after one was started, so the page can reload. */
  onSynced?: () => void;
  /** Called after the source settings were saved without syncing. */
  onSaved?: () => void;
  /** Called once the server has accepted a sync, so the page can follow it. */
  onStarted?: () => void;
}

/** Collects the source database details, confirms the wipe, then follows the sync. */
export function SyncDialog({ app, service, open, onOpenChange, initialSource, database, onSynced, onSaved, onStarted }: Props) {
  // A hidden dialog can't report the sync finishing, so closing after a start refreshes the page instead.
  const started = useRef(false);
  const change = (next: boolean) => {
    if (!next && started.current) {
      started.current = false;
      onSynced?.();
    }
    onOpenChange(next);
  };
  return (
    <Dialog open={open} onOpenChange={change}>
      <DialogContent className="sm:max-w-2xl">
        {/* Mounted only while open so every opening starts from the saved source. */}
        {open && (
          <SyncBody
            app={app} service={service} initialSource={initialSource} database={database}
            onSynced={onSynced}
            onSaved={onSaved}
            onStarted={() => {
              started.current = true;
              onStarted?.();
            }}
            onClose={() => change(false)}
          />
        )}
      </DialogContent>
    </Dialog>
  );
}

function SyncBody({
  app,
  service,
  initialSource,
  database,
  onSynced,
  onSaved,
  onStarted,
  onClose,
}: Pick<Props, "app" | "service" | "initialSource" | "database" | "onSynced" | "onSaved"> & { onStarted: () => void; onClose: () => void }) {
  const [local, setLocal] = useState(database);
  const [step, setStep] = useState<Step>("form");
  const [loaded, setLoaded] = useState(false);
  const [source, setSource] = useState<SourceSettings>(initialSource ?? EMPTY);
  const [password, setPassword] = useState("");
  // The password saved on this laptop, filled into the form so it can be revealed or changed.
  const [savedPassword, setSavedPassword] = useState<string>();
  const [shown, setShown] = useState(false);
  const [invalid, setInvalid] = useState<Partial<Record<FieldName, string>>>({});
  const [job, setJob] = useState<SyncJob>();
  const [error, setError] = useState<string>();
  const [starting, setStarting] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .db(app, service)
      .source()
      .then(async (info) => {
        if (cancelled) return;
        if (!initialSource && info.source) setSource(info.source);
        // A caller's own source may point elsewhere, so the saved password is only filled in for the saved source.
        const saved = info.passwordSaved && !initialSource ? (await api.db(app, service).savedPassword()).password : null;
        if (cancelled) return;
        if (saved) {
          setSavedPassword(saved);
          setPassword(saved);
        }
        // A sync that is still running is the thing to look at, not a form.
        if (info.job?.state === "running") {
          setJob(info.job);
          setStep("progress");
        }
      })
      .catch((e: Error) => !cancelled && setError(e.message))
      .finally(() => !cancelled && setLoaded(true));
    return () => {
      cancelled = true;
    };
  }, [app, service, initialSource]);

  useEffect(() => {
    let cancelled = false;
    api
      .status(app)
      .then((status) => {
        const name = status.services.find((svc) => svc.id === service)?.options.name;
        if (!cancelled && name) setLocal(name);
      })
      .catch(() => undefined); // The confirmation keeps its spinner; the sync itself reports real problems.
    return () => {
      cancelled = true;
    };
  }, [app, service]);

  const set = (patch: Partial<SourceSettings>, field: FieldName) => {
    setSource((s) => ({ ...s, ...patch }));
    setInvalid((i) => ({ ...i, [field]: undefined }));
  };

  const review = () => {
    const missing: Partial<Record<FieldName, string>> = {};
    if (!source.host.trim()) missing.host = "Required";
    if (!source.port) missing.port = "Required";
    if (!source.database.trim()) missing.database = "Required";
    if (!source.user.trim()) missing.user = "Required";
    if (!password) missing.password = "Required";
    setInvalid(missing);
    setError(undefined);
    if (Object.keys(missing).length === 0) setStep("confirm");
  };

  const sourceBody = () => ({ ...source, host: source.host.trim(), database: source.database.trim(), user: source.user.trim() });

  const save = async () => {
    setSaving(true);
    setError(undefined);
    try {
      await api.db(app, service).saveSource(sourceBody(), password);
      toast.success(password ? "Source settings and password saved" : "Source settings saved");
      onSaved?.();
      onClose();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const start = async () => {
    setStarting(true);
    setError(undefined);
    const body = sourceBody();
    try {
      setJob(await api.db(app, service).startSync(body, password));
      onStarted();
      setStep("progress");
    } catch (e) {
      setStep("form");
      if (e instanceof ApiError && e.status === 409 && e.body.field === "password") setInvalid({ password: "Authentication failed" });
      else if (e instanceof ApiError && e.status === 400 && Array.isArray(e.body.missing)) {
        setInvalid(Object.fromEntries((e.body.missing as string[]).map((f) => [f, "Required"])));
      } else if (e instanceof ApiError && e.status === 502) setError(`${e.message}. Check your VPN or tunnel and that the host and port are right.`);
      else setError((e as Error).message);
    } finally {
      setStarting(false);
    }
  };

  const label = serviceLabel(service);
  const target = `${source.user}@${source.host}:${source.port}/${source.database}`;

  if (step === "progress" && job) {
    return (
      <>
        <DialogHeader>
          <DialogTitle>Syncing {label}</DialogTitle>
          <DialogDescription className="wrap-anywhere">
            Copying {target} into {app}.
          </DialogDescription>
        </DialogHeader>
        <SyncProgress
          app={app}
          service={service}
          initial={job}
          onRetry={() => setStep("form")}
          onClose={onClose}
          onSucceeded={() => {
            toast.success(`Synced ${label} of ${app}`);
            onSynced?.();
          }}
        />
      </>
    );
  }

  if (step === "confirm") {
    return (
      <>
        <DialogHeader>
          <DialogTitle>Replace local data?</DialogTitle>
          <DialogDescription className="wrap-anywhere">
            {local ? (
              `Replace all data in ${local} of ${app} with a copy of ${target}? Local data will be deleted.`
            ) : (
              <Spinner />
            )}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" disabled={starting} onClick={() => setStep("form")}>
            Back
          </Button>
          <Button variant="destructive" disabled={starting || !local} onClick={start}>
            {starting ? "Checking…" : "Replace and sync"}
          </Button>
        </DialogFooter>
      </>
    );
  }

  const field = (name: FieldName) => ({ "aria-invalid": invalid[name] ? true : undefined, id: `sync-${name}`, spellCheck: false });
  return (
    <>
      <DialogHeader>
        <DialogTitle>Sync from source</DialogTitle>
        <DialogDescription>Copy a {label} database into this app's local one, replacing what is there.</DialogDescription>
      </DialogHeader>
      {!loaded ? (
        <Skeleton className="h-56 w-full" />
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            review();
          }}
        >
          <FieldGroup className="gap-4">
            <div className="grid grid-cols-3 gap-3">
              <Field className="col-span-2" data-invalid={!!invalid.host}>
                <FieldLabel htmlFor="sync-host">Host</FieldLabel>
                <Input {...field("host")} value={source.host} placeholder="db.example.com" onChange={(e) => set({ host: e.target.value }, "host")} />
                <FieldError>{invalid.host}</FieldError>
              </Field>
              <Field data-invalid={!!invalid.port}>
                <FieldLabel htmlFor="sync-port">Port</FieldLabel>
                <Input
                  {...field("port")}
                  inputMode="numeric"
                  value={source.port ? String(source.port) : ""}
                  onChange={(e) => set({ port: Number.parseInt(e.target.value.replace(/\D/g, ""), 10) || 0 }, "port")}
                />
                <FieldError>{invalid.port}</FieldError>
              </Field>
              <Field className="col-span-2" data-invalid={!!invalid.database}>
                <FieldLabel htmlFor="sync-database">Database</FieldLabel>
                <Input {...field("database")} value={source.database} onChange={(e) => set({ database: e.target.value }, "database")} />
                <FieldError>{invalid.database}</FieldError>
              </Field>
              <Field data-invalid={!!invalid.user}>
                <FieldLabel htmlFor="sync-user">User</FieldLabel>
                <Input {...field("user")} value={source.user} onChange={(e) => set({ user: e.target.value }, "user")} />
                <FieldError>{invalid.user}</FieldError>
              </Field>
            </div>
            <Field data-invalid={!!invalid.password}>
              <FieldLabel htmlFor="sync-password">Password</FieldLabel>
              <div className="flex gap-1">
                <Input
                  {...field("password")}
                  type={shown ? "text" : "password"}
                  autoComplete="off"
                  value={password}
                  onChange={(e) => {
                    setPassword(e.target.value);
                    setInvalid((i) => ({ ...i, password: undefined }));
                  }}
                />
                <Button type="button" variant="outline" size="icon" onClick={() => setShown(!shown)} aria-label={shown ? "Hide password" : "Reveal password"}>
                  {shown ? <EyeOffIcon /> : <EyeIcon />}
                </Button>
              </div>
              {savedPassword !== undefined && password === savedPassword && !invalid.password && (
                <FieldDescription className="text-xs">Saved on this laptop.</FieldDescription>
              )}
              <FieldError>{invalid.password}</FieldError>
            </Field>
          </FieldGroup>
          <ErrorAlert className="mt-4" message={error} onDismiss={() => setError(undefined)} />
          <DialogFooter className="mt-4">
            <Button type="button" variant="outline" onClick={onClose}>
              Cancel
            </Button>
            <Button type="button" variant="outline" disabled={saving || !source.host.trim() || !source.port || !source.database.trim() || !source.user.trim()} onClick={save}>
              {saving ? "Saving…" : "Save"}
            </Button>
            <Button type="submit">Sync</Button>
          </DialogFooter>
        </form>
      )}
    </>
  );
}
