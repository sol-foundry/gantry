import { useEffect, useRef, useState } from "react";
import { AlertTriangleIcon, EyeIcon, EyeOffIcon } from "lucide-react";
import { toast } from "sonner";
import { api } from "@/api";
import { ErrorAlert } from "@/components/common";
import { OptionField } from "@/components/OptionField";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Field, FieldDescription, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Textarea } from "@/components/ui/textarea";
import { useAction } from "@/hooks";
import { useGantry } from "@/lib/gantry";
import { href, navigate } from "@/lib/router";
import { ServiceIcon, serviceLabel } from "@/lib/services";
import type { CatalogService, ImportApplied, ImportPreview, ImportRequest, ImportedService, SourceSettings, SqlService } from "@/types";

const MIGRATIONS = "migrations";
const VALIDATE_DEBOUNCE_MS = 300;
const KEPT_SECRET_WARNING = "The existing database won't accept this until its volume is recreated.";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Called after the app is created; `servicesWithSource` are the services whose source settings are complete. */
  onImported?: (app: string, servicesWithSource: string[]) => void;
}

/** Imports a shared .gantry.json: pick or paste the file, edit everything in a preview, then confirm. */
export function ImportDialog({ open, onOpenChange, onImported }: Props) {
  const [preview, setPreview] = useState<ImportPreview>();
  // Kept so a rename can ask the server for a fresh preview of the same file.
  const [file, setFile] = useState("");
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        onOpenChange(next);
        if (!next) setPreview(undefined);
      }}
    >
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Import app</DialogTitle>
          <DialogDescription>
            {preview ? "Review and edit everything before the app is created." : "Choose a .gantry.json file or paste its contents."}
          </DialogDescription>
        </DialogHeader>
        {preview ? (
          <PreviewStep file={file} initial={preview} onBack={() => setPreview(undefined)} onClose={() => onOpenChange(false)} onImported={onImported} />
        ) : (
          <FileStep
            onPreview={(result, text) => {
              setFile(text);
              setPreview(result);
            }}
            onCancel={() => onOpenChange(false)} />
        )}
      </DialogContent>
    </Dialog>
  );
}

function FileStep({ onPreview, onCancel }: { onPreview: (preview: ImportPreview, text: string) => void; onCancel: () => void }) {
  const [text, setText] = useState("");
  const { busy, error, run, clearError } = useAction();
  const input = useRef<HTMLInputElement>(null);

  const load = async (file: File | undefined) => {
    if (!file) return;
    await run("read", async () => setText(await file.text()));
  };
  const next = async () => {
    let result: ImportPreview | undefined;
    if (await run("preview", async () => void (result = await api.importPreview(text)))) onPreview(result!, text);
  };

  return (
    <>
      <FieldGroup className="gap-4">
        <Field>
          <FieldLabel htmlFor="import-file">File</FieldLabel>
          <Input id="import-file" ref={input} type="file" accept=".json,application/json" onChange={(e) => load(e.target.files?.[0])} />
        </Field>
        <Field>
          <FieldLabel htmlFor="import-text">Or paste the JSON</FieldLabel>
          <Textarea
            id="import-text"
            className="max-h-[40vh] min-h-40 font-mono text-xs"
            value={text}
            spellCheck={false}
            placeholder='{ "gantry": 1, "app": "my-app", ... }'
            onChange={(e) => setText(e.target.value)}
          />
        </Field>
      </FieldGroup>
      <ErrorAlert message={error} onDismiss={clearError} />
      <DialogFooter>
        <Button variant="outline" onClick={onCancel}>
          Cancel
        </Button>
        <Button disabled={!text.trim() || !!busy} onClick={next}>
          {busy ? "Reading…" : "Preview"}
        </Button>
      </DialogFooter>
    </>
  );
}

function hasSource(source: SourceSettings | null | undefined): source is SourceSettings {
  return !!source && !!source.host && source.port > 0 && !!source.database && !!source.user;
}

/** Any of host, port or database: enough to send, since the server fills an empty user from the one saved locally. */
function anySource(source: SourceSettings | null | undefined): source is SourceSettings {
  return !!source && (!!source.host.trim() || source.port > 0 || !!source.database.trim());
}

const isSql = (id: string): id is SqlService => id === "postgres" || id === "mysql";

const NAME_RULE = /^[a-z][a-z0-9-]*$/;
const RENAME_DEBOUNCE_MS = 400;

function PreviewStep({
  file,
  initial,
  onBack,
  onClose,
  onImported,
}: {
  file: string;
  initial: ImportPreview;
  onBack: () => void;
  onClose: () => void;
  onImported?: (app: string, servicesWithSource: string[]) => void;
}) {
  const { catalog, reloadApps } = useGantry();
  // The latest server preview: secrets, kept secrets, warnings and checkout guesses belong to the name it was made for.
  const [preview, setPreview] = useState(initial);
  const [request, setRequest] = useState<ImportRequest>(initial.request);
  const [repreviewing, setRepreviewing] = useState(false);
  const [reviewError, setReviewError] = useState<string>();
  // "service.option" keys the user changed; a re-preview must not overwrite them.
  const edited = useRef(new Set<string>());
  const [validation, setValidation] = useState<{ errors: string[]; exists: boolean }>({ errors: [], exists: initial.exists });
  const [checking, setChecking] = useState(false);
  const { busy, error, run, clearError } = useAction();

  useEffect(() => {
    let cancelled = false;
    setChecking(true);
    const timer = setTimeout(() => {
      api
        .importValidate({ ...request, app: request.app.trim() })
        .then((v) => !cancelled && setValidation(v))
        .catch((e: Error) => !cancelled && setValidation((v) => ({ ...v, errors: [e.message] })))
        .finally(() => !cancelled && setChecking(false));
    }, VALIDATE_DEBOUNCE_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [request]);

  const name = request.app.trim();
  const needsPreview = NAME_RULE.test(name) && name !== preview.request.app;

  useEffect(() => {
    if (!needsPreview) return;
    let cancelled = false;
    const timer = setTimeout(() => {
      setRepreviewing(true);
      setReviewError(undefined);
      api
        .importPreview(file, name)
        .then((next) => {
          if (cancelled) return;
          setPreview(next);
          setReviewError(undefined);
          setRequest((r) => ({
            ...r,
            services: Object.fromEntries(
              Object.entries(r.services).map(([id, current]) => {
                const fresh = next.request.services[id];
                if (!fresh) return [id, current];
                const options = Object.fromEntries(
                  Object.entries(fresh.options).map(([key, value]) => [key, edited.current.has(`${id}.${key}`) ? (current.options[key] ?? value) : value]),
                );
                return [id, { ...current, options }];
              }),
            ),
          }));
        })
        .catch((e: Error) => !cancelled && setReviewError(e.message))
        .finally(() => !cancelled && setRepreviewing(false));
    }, RENAME_DEBOUNCE_MS);
    return () => {
      cancelled = true;
      clearTimeout(timer);
      setRepreviewing(false);
    };
  }, [needsPreview, name, file]);

  const setService = (id: string, patch: (s: ImportedService) => ImportedService) =>
    setRequest((r) => ({ ...r, services: { ...r.services, [id]: patch(r.services[id]!) } }));
  const markEdited = (id: string, key: string) => edited.current.add(`${id}.${key}`);

  const apply = async () => {
    const body: ImportRequest = {
      ...request,
      app: request.app.trim(),
      services: Object.fromEntries(Object.entries(request.services).map(([id, s]) => [id, { ...s, source: anySource(s.source) ? s.source : null }])),
    };
    let app = body.app;
    let applied: ImportApplied | undefined;
    if (await run("apply", async () => void (app = (applied = await api.importApply(body)).app))) {
      toast.success(validation.exists ? `Replaced ${app}` : `Imported ${app}`);
      // Secret values and authorizer images never travel in the file: say what is left to do on this machine.
      Object.entries(applied?.toPush ?? {}).forEach(([name, dir]) =>
        toast.info(`Authorizer ${name} is not built on this machine`, { description: `gantry authorizer push ${app} ${name} ${dir}`, duration: Infinity }),
      );
      (applied?.unsetSecrets ?? []).forEach((name) =>
        toast.info(`Secret ${name} is not set`, { description: `gantry secret set ${app} ${name}`, duration: Infinity }),
      );
      reloadApps();
      onClose();
      navigate(href.app(app));
      // The server's merged source decides the offer: it may have filled in the developer's own user.
      const sent = Object.entries(body.services).filter(([id, s]) => isSql(id) && anySource(s.source)).map(([id]) => id).filter(isSql);
      const complete = await Promise.all(
        sent.map((id) =>
          api
            .db(app, id)
            .source()
            .then((info) => (hasSource(info.source) ? id : null))
            .catch(() => null),
        ),
      );
      onImported?.(app, complete.filter((id): id is SqlService => id !== null));
    }
  };

  const services = Object.entries(request.services);
  const envKeys = Object.keys(request.envNames);
  const blocked = validation.errors.length > 0 || !name || checking || repreviewing || needsPreview || !!reviewError;

  return (
    <>
      <ScrollArea className="max-h-[70vh] pr-3">
        <FieldGroup className="gap-5">
          <Field>
            <div className="flex items-center justify-between gap-2">
              <FieldLabel htmlFor="import-app">App name</FieldLabel>
              <Badge variant={validation.exists ? "secondary" : "outline"}>{validation.exists ? "exists — will replace config" : "new"}</Badge>
            </div>
            <Input
              id="import-app"
              value={request.app}
              spellCheck={false}
              onChange={(e) => setRequest((r) => ({ ...r, app: e.target.value }))}
            />
            {validation.exists && (
              <FieldDescription className="text-xs">
                An app with this name already exists. Confirming replaces its services, options, env names and source settings; its data volumes
                are kept and the change applies on the next Start. Services not in the file are removed.
              </FieldDescription>
            )}
          </Field>

          {preview.warnings.length > 0 && (
            <Alert>
              <AlertTriangleIcon />
              <AlertDescription>
                <ul className="list-disc pl-4">
                  {preview.warnings.map((w) => (
                    <li key={w}>{w}</li>
                  ))}
                </ul>
              </AlertDescription>
            </Alert>
          )}

          {services.map(([id, service]) => (
            <ServiceSection
              key={id}
              id={id}
              service={service}
              def={catalog?.services.find((s) => s.id === id)}
              kept={preview.keptSecrets.filter((k) => k.startsWith(`${id}.`)).map((k) => k.slice(id.length + 1))}
              original={preview.request.services[id]?.options ?? {}}
              guess={preview.checkoutGuesses[id]}
              onEdit={(key) => markEdited(id, key)}
              onChange={(patch) => setService(id, patch)}
            />
          ))}

          {envKeys.length > 0 && (
            <section className="flex flex-col gap-2">
              <h3 className="text-sm font-medium">Environment variable names</h3>
              <ul className="divide-y rounded-lg border font-mono text-xs">
                {envKeys.map((key) => {
                  const omitted = request.envNames[key] === "";
                  return (
                    <li key={key} className="flex flex-col gap-1 px-2.5 py-1.5">
                      <div className="flex items-center justify-between gap-2 text-muted-foreground">
                        <span className="truncate">{key}</span>
                        <label className="flex shrink-0 items-center gap-1.5 font-sans">
                          <Checkbox
                            checked={omitted}
                            onCheckedChange={(checked) => setRequest((r) => ({ ...r, envNames: { ...r.envNames, [key]: checked ? "" : key } }))}
                          />
                          Omit
                        </label>
                      </div>
                      <Input
                        aria-label={`Name for ${key}`}
                        className="h-7 font-mono text-xs"
                        value={omitted ? "" : request.envNames[key]}
                        disabled={omitted}
                        spellCheck={false}
                        onChange={(e) => setRequest((r) => ({ ...r, envNames: { ...r.envNames, [key]: e.target.value } }))}
                      />
                    </li>
                  );
                })}
              </ul>
            </section>
          )}
        </FieldGroup>
      </ScrollArea>
      <ErrorAlert message={reviewError} title="Could not refresh the preview for this name" />
      {validation.errors.length > 0 && (
        <ErrorAlert message={validation.errors.join("\n")} title="Fix these before importing" />
      )}
      <ErrorAlert message={error} onDismiss={clearError} />
      <DialogFooter>
        <Button variant="outline" disabled={!!busy} onClick={onBack}>
          Back
        </Button>
        <Button disabled={blocked || !!busy} onClick={apply}>
          {busy ? "Importing…" : validation.exists ? "Replace config" : "Import"}
        </Button>
      </DialogFooter>
    </>
  );
}

function ServiceSection({
  id,
  service,
  def,
  kept,
  original,
  guess,
  onEdit,
  onChange,
}: {
  id: string;
  service: ImportedService;
  def: CatalogService | undefined;
  kept: string[];
  original: Record<string, string>;
  guess: string | undefined;
  onEdit: (key: string) => void;
  onChange: (patch: (s: ImportedService) => ImportedService) => void;
}) {
  const setOption = (key: string, value: string) => {
    onEdit(key);
    onChange((s) => ({ ...s, options: { ...s.options, [key]: value } }));
  };
  const sqlSource = def?.options[MIGRATIONS] !== undefined || !!service.source;
  const source = service.source ?? { host: "", port: 0, database: "", user: "" };
  const setSource = (patch: Partial<SourceSettings>) => onChange((s) => ({ ...s, source: { ...source, ...patch } }));

  return (
    <section className="flex flex-col gap-3 rounded-lg border p-3">
      <h3 className="flex items-center gap-2 text-sm font-medium">
        <ServiceIcon id={id} className="size-4" />
        {serviceLabel(id)}
      </h3>
      {!def ? (
        <p className="text-xs text-muted-foreground">Unknown service; it will be reported above.</p>
      ) : (
        <FieldGroup className="gap-4">
          {def.optionOrder
            .filter((key) => key !== MIGRATIONS)
            .map((key) =>
              def.optionMeta[key]?.secret ? (
                <SecretField
                  key={key}
                  id={`${id}-${key}`}
                  name={key}
                  value={service.options[key] ?? ""}
                  kept={kept.includes(key)}
                  changed={service.options[key] !== original[key]}
                  onChange={(value) => setOption(key, value)}
                />
              ) : (
                <OptionField
                  key={key}
                  name={key}
                  idPrefix={`import-${id}`}
                  meta={def.optionMeta[key]}
                  defaultValue={def.options[key] ?? ""}
                  value={service.options[key]}
                  onChange={(value) => setOption(key, value)}
                />
              ),
            )}
          {def.optionOrder.includes(MIGRATIONS) && (
            <Field>
              <FieldLabel htmlFor={`${id}-migrations`} className="font-mono text-xs">
                migrations folder
              </FieldLabel>
              <Input
                id={`${id}-migrations`}
                className="font-mono text-xs"
                value={service.options[MIGRATIONS] ?? ""}
                placeholder="/path/to/your/checkout/migrations"
                spellCheck={false}
                onChange={(e) => setOption(MIGRATIONS, e.target.value)}
              />
              <FieldDescription className="text-xs">
                {guess ? `Best guess for your checkout: ${guess}` : "Where this repo is checked out on your machine; leave empty to skip."}
              </FieldDescription>
            </Field>
          )}
        </FieldGroup>
      )}
      {sqlSource && (
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-xs font-medium text-muted-foreground">Source database (the password is not shared)</legend>
          <div className="grid grid-cols-3 gap-2">
            <Field className="col-span-2">
              <FieldLabel htmlFor={`${id}-host`} className="text-xs">
                Host
              </FieldLabel>
              <Input id={`${id}-host`} value={source.host} spellCheck={false} onChange={(e) => setSource({ host: e.target.value })} />
            </Field>
            <Field>
              <FieldLabel htmlFor={`${id}-port`} className="text-xs">
                Port
              </FieldLabel>
              <Input
                id={`${id}-port`}
                inputMode="numeric"
                value={source.port ? String(source.port) : ""}
                onChange={(e) => setSource({ port: Number.parseInt(e.target.value.replace(/\D/g, ""), 10) || 0 })}
              />
            </Field>
            <Field className="col-span-2">
              <FieldLabel htmlFor={`${id}-database`} className="text-xs">
                Database
              </FieldLabel>
              <Input id={`${id}-database`} value={source.database} spellCheck={false} onChange={(e) => setSource({ database: e.target.value })} />
            </Field>
            <Field>
              <FieldLabel htmlFor={`${id}-user`} className="text-xs">
                User
              </FieldLabel>
              <Input id={`${id}-user`} value={source.user} spellCheck={false} onChange={(e) => setSource({ user: e.target.value })} />
            </Field>
          </div>
        </fieldset>
      )}
    </section>
  );
}

function SecretField({
  id,
  name,
  value,
  kept,
  changed,
  onChange,
}: {
  id: string;
  name: string;
  value: string;
  kept: boolean;
  changed: boolean;
  onChange: (value: string) => void;
}) {
  const [shown, setShown] = useState(false);
  return (
    <Field>
      <div className="flex items-center gap-2">
        <FieldLabel htmlFor={id} className="font-mono text-xs">
          {name}
        </FieldLabel>
        <Badge variant="outline" className="font-normal">
          {kept ? "kept from your app" : "generated"}
        </Badge>
      </div>
      <div className="flex gap-1">
        <Input id={id} type={shown ? "text" : "password"} className="font-mono text-xs" value={value} spellCheck={false} autoComplete="off" onChange={(e) => onChange(e.target.value)} />
        <Button variant="outline" size="icon" onClick={() => setShown(!shown)} aria-label={shown ? "Hide value" : "Reveal value"}>
          {shown ? <EyeOffIcon /> : <EyeIcon />}
        </Button>
      </div>
      {kept && changed && <FieldDescription className="text-xs text-destructive">{KEPT_SECRET_WARNING}</FieldDescription>}
    </Field>
  );
}
