import { useState } from "react";
import { CheckIcon, PlusIcon } from "lucide-react";
import { toast } from "sonner";
import { api } from "@/api";
import { ErrorAlert, PageHeader } from "@/components/common";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Field, FieldContent, FieldDescription, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { useGantry } from "@/lib/gantry";
import { href, navigate } from "@/lib/router";
import { ServiceIcon, serviceLabel } from "@/lib/services";
import { cn } from "@/lib/utils";
import type { CatalogService } from "@/types";

const NAME_RULE = /^[a-z][a-z0-9-]*$/;
const NO_PRESET = "none";

type Step = "creating" | "starting";

export function NewAppPage() {
  const { catalog, apps, reloadApps } = useGantry();
  const [name, setName] = useState("");
  const [touched, setTouched] = useState(false);
  const [preset, setPreset] = useState(NO_PRESET);
  const [selected, setSelected] = useState<string[]>([]);
  const [start, setStart] = useState(true);
  const [step, setStep] = useState<Step>();
  const [error, setError] = useState<string>();

  const services = catalog?.services ?? [];
  const nameError = !name
    ? "Name is required"
    : !NAME_RULE.test(name)
      ? "Use lowercase letters, digits and dashes, starting with a letter"
      : apps?.some((a) => a.name === name)
        ? `An app named ${name} already exists`
        : undefined;
  const nothingChosen = preset === NO_PRESET && selected.length === 0;

  /** Checking a service also checks what it requires; unchecking one also unchecks what depends on it. */
  const toggle = (service: CatalogService) => {
    if (selected.includes(service.id)) {
      const dependents = services.filter((s) => s.requires.includes(service.id)).map((s) => s.id);
      setSelected(selected.filter((id) => id !== service.id && !dependents.includes(id)));
    } else {
      setSelected([...new Set([...selected, service.id, ...service.requires])]);
    }
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setTouched(true);
    if (nameError) return;
    setError(undefined);
    try {
      setStep("creating");
      await api.createApp(name, preset === NO_PRESET ? "" : preset, selected);
      reloadApps();
      if (start && !nothingChosen) {
        setStep("starting");
        const { notes } = await api.up(name);
        notes.forEach((note) => toast.info(note));
        toast.success(`${name} is up`);
      } else {
        toast.success(`Created ${name}`);
      }
      reloadApps();
      navigate(href.app(name));
    } catch (err) {
      // Creation may have succeeded before starting failed; the app page shows what exists.
      setError((err as Error).message);
      reloadApps();
    } finally {
      setStep(undefined);
    }
  };

  return (
    <>
      <PageHeader title="New app" description="An app is one isolated set of local services: give it a name and pick what it needs." />
      {/* Only the services list gives up height (and scrolls); the name, start switch and buttons stay in view. */}
      <form onSubmit={submit} className={cn("flex flex-col gap-6", "max-w-4xl")}>
        <Card size="sm" className="shrink-0">
          <CardHeader>
            <CardTitle>Name</CardTitle>
            <CardDescription>Usually the name of the microservice that will use it. It prefixes containers and volumes.</CardDescription>
          </CardHeader>
          <CardContent>
            <FieldGroup className="gap-4 sm:flex-row">
              <Field data-invalid={touched && !!nameError} className="sm:flex-1">
                <FieldLabel htmlFor="app-name">App name</FieldLabel>
                <Input
                  id="app-name"
                  autoFocus
                  value={name}
                  placeholder="my-app"
                  className="font-mono"
                  aria-invalid={touched && !!nameError}
                  spellCheck={false}
                  onChange={(e) => setName(e.target.value.trim())}
                  onBlur={() => setTouched(true)}
                />
                {touched && nameError && <FieldError>{nameError}</FieldError>}
              </Field>
              <Field className="sm:w-56">
                <FieldLabel>Preset</FieldLabel>
                <Select
                  value={preset}
                  items={[{ value: NO_PRESET, label: "No preset" }, ...(catalog?.presets ?? []).map((p) => ({ value: p, label: p }))]}
                  onValueChange={(value) => setPreset(value ?? NO_PRESET)}
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={NO_PRESET}>No preset</SelectItem>
                    {catalog?.presets.map((p) => (
                      <SelectItem key={p} value={p}>
                        {p}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <FieldDescription className="text-xs">Adds the preset's services, plus any you check below.</FieldDescription>
              </Field>
            </FieldGroup>
          </CardContent>
        </Card>

        <Card size="sm">
          <CardHeader>
            <CardTitle>Services</CardTitle>
            <CardDescription>Pick the infrastructure this app needs. You can add or remove services later.</CardDescription>
          </CardHeader>
          <CardContent>
            {catalog === undefined && <Skeleton className="h-40 w-full" />}
            <div className="grid gap-2 sm:grid-cols-2">
              {services.map((s) => {
                const checked = selected.includes(s.id);
                return (
                  <button
                    key={s.id}
                    type="button"
                    role="checkbox"
                    aria-checked={checked}
                    onClick={() => toggle(s)}
                    className={cn(
                      "flex items-start gap-3 rounded-lg border p-3 text-left transition-colors hover:bg-muted/60 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none",
                      checked && "border-primary bg-muted/60",
                    )}
                  >
                    <div className="flex size-8 shrink-0 items-center justify-center rounded-md border bg-background">
                      <ServiceIcon id={s.id} className="size-4" />
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="font-medium">{serviceLabel(s.id)}</span>
                        <span className="font-mono text-xs text-muted-foreground">{s.id}</span>
                      </div>
                      <p className="line-clamp-2 text-xs text-muted-foreground">{s.description}</p>
                      {s.requires.length > 0 && (
                        <p className="mt-1 text-xs text-muted-foreground">Requires {s.requires.map(serviceLabel).join(", ")}</p>
                      )}
                    </div>
                    <span
                      className={cn(
                        "flex size-4 shrink-0 items-center justify-center rounded-[4px] border",
                        checked && "border-primary bg-primary text-primary-foreground",
                      )}
                    >
                      {checked && <CheckIcon className="size-3" />}
                    </span>
                  </button>
                );
              })}
            </div>
          </CardContent>
        </Card>

        <Field orientation="horizontal" className="max-w-md shrink-0">
          <FieldContent>
            <FieldLabel htmlFor="start-now">Start it now</FieldLabel>
            <FieldDescription className="text-xs">Pull images and start the containers right after creating the app.</FieldDescription>
          </FieldContent>
          <Switch id="start-now" checked={start} disabled={nothingChosen} onCheckedChange={setStart} />
        </Field>

        <ErrorAlert message={error} title="Could not create the app" className="shrink-0" />

        <div className="flex shrink-0 items-center gap-2">
          <Button type="submit" disabled={!!step || (touched && !!nameError)}>
            {step ? <Spinner /> : <PlusIcon />}
            {step === "creating" ? "Creating…" : step === "starting" ? "Starting containers…" : "Create app"}
          </Button>
          <Button type="button" variant="ghost" nativeButton={false} render={<a href={href.home()} />}>
            Cancel
          </Button>
          {step === "starting" && (
            <span className="text-sm text-muted-foreground">First start can take a minute while images download.</span>
          )}
        </div>
      </form>
    </>
  );
}
