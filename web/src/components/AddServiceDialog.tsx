import { useEffect, useState } from "react";
import { CheckIcon, PlusIcon, TriangleAlertIcon } from "lucide-react";
import { toast } from "sonner";
import { api } from "@/api";
import { ErrorAlert } from "@/components/common";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Checkbox } from "@/components/ui/checkbox";
import { OptionField } from "@/components/OptionField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Field, FieldGroup, FieldLabel } from "@/components/ui/field";
import { ScrollArea } from "@/components/ui/scroll-area";
import { useAction } from "@/hooks";
import { ServiceIcon, serviceLabel } from "@/lib/services";
import { cn } from "@/lib/utils";
import type { CatalogService, ServiceChange } from "@/types";

interface Props {
  app: string;
  catalog: CatalogService[];
  existing: string[];
  onAdded: () => void;
}

/** Picks a catalog service the app doesn't have yet and its options; containers change on the next Start. */
export function AddServiceDialog({ app, catalog, existing, onAdded }: Props) {
  const [open, setOpen] = useState(false);
  const [serviceId, setServiceId] = useState("");
  const [options, setOptions] = useState<Record<string, string>>({});
  // The data volume a removed copy of this database left behind; its old credentials won't match the new ones.
  const [leftover, setLeftover] = useState<string | null>(null);
  const [freshData, setFreshData] = useState(true);
  const { busy, error, run, clearError } = useAction();
  const available = catalog.filter((s) => !existing.includes(s.id));
  const service = available.find((s) => s.id === serviceId);

  const choose = (id: string) => {
    setServiceId(id);
    setOptions({});
    setFreshData(true);
    clearError();
  };

  useEffect(() => {
    setLeftover(null);
    if (!serviceId) return;
    let cancelled = false;
    api
      .leftoverVolume(app, serviceId)
      .then((r) => !cancelled && setLeftover(r.volume))
      .catch(() => undefined); // Without Docker there is nothing to warn about.
    return () => {
      cancelled = true;
    };
  }, [app, serviceId]);

  const close = (next: boolean) => {
    setOpen(next);
    if (!next) choose("");
  };
  const submit = async () => {
    if (!service) return;
    let change: ServiceChange | undefined;
    const add = async () => (change = await api.addService(app, service.id, options, !!leftover && freshData));
    if (await run("add", add)) {
      toast.success(`Added ${serviceLabel(service.id)}`, { description: change?.applied ? "It's running." : "Apply it with Start." });
      change?.notes.forEach((note) => toast.info(note));
      close(false);
      onAdded();
    }
  };

  return (
    <>
      <Button size="sm" onClick={() => setOpen(true)} disabled={available.length === 0}>
        <PlusIcon />
        Add service
      </Button>
      <Dialog open={open} onOpenChange={close}>
        <DialogContent className="sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>Add a service to {app}</DialogTitle>
            <DialogDescription>Pick a service, adjust its options, then press Start to create its container.</DialogDescription>
          </DialogHeader>
          <ScrollArea className="max-h-[60vh] pr-3">
            <div className="flex flex-col gap-4">
              <div className="grid gap-2 sm:grid-cols-2">
                {available.map((s) => (
                  <button
                    key={s.id}
                    type="button"
                    onClick={() => choose(s.id)}
                    className={cn(
                      "flex items-start gap-2.5 rounded-lg border p-2.5 text-left text-sm transition-colors hover:bg-muted/60",
                      s.id === serviceId && "border-primary bg-muted/60",
                    )}
                  >
                    <ServiceIcon id={s.id} className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
                    <span className="min-w-0 flex-1">
                      <span className="block font-medium">{serviceLabel(s.id)}</span>
                      <span className="line-clamp-2 text-xs text-muted-foreground">{s.description}</span>
                    </span>
                    {s.id === serviceId && <CheckIcon className="size-4 shrink-0" />}
                  </button>
                ))}
              </div>
              {service && (
                <FieldGroup className="gap-4">
                  {service.requires.length > 0 && (
                    <p className="text-xs text-muted-foreground">Also adds: {service.requires.map(serviceLabel).join(", ")}</p>
                  )}
                  {leftover && (
                    <Alert>
                      <TriangleAlertIcon />
                      <AlertTitle>Old data found</AlertTitle>
                      <AlertDescription className="flex flex-col gap-2">
                        <span>
                          A {serviceLabel(service.id)} removed from {app} earlier left its data in{" "}
                          <span className="font-mono text-xs">{leftover}</span>, with the user and password it was created with. A
                          new password won't work with it.
                        </span>
                        <Field orientation="horizontal">
                          <Checkbox id="fresh-data" checked={freshData} onCheckedChange={(checked) => setFreshData(checked === true)} />
                          <FieldLabel htmlFor="fresh-data" className="font-normal">
                            Delete the old data and start empty
                          </FieldLabel>
                        </Field>
                        {!freshData && <span>To reuse it, set user and password below to the old ones.</span>}
                      </AlertDescription>
                    </Alert>
                  )}
                  {service.optionOrder.map((key) => (
                    <OptionField
                      key={key}
                      name={key}
                      meta={service.optionMeta[key]}
                      defaultValue={service.optionMeta[key]?.secret ? "generated" : (service.options[key] ?? "")}
                      value={options[key]}
                      onChange={(value) => setOptions({ ...options, [key]: value })}
                    />
                  ))}
                </FieldGroup>
              )}
            </div>
          </ScrollArea>
          <ErrorAlert message={error} />
          <DialogFooter>
            <Button variant="outline" onClick={() => close(false)}>
              Cancel
            </Button>
            <Button disabled={!service || !!busy} onClick={submit}>
              {busy ? "Adding…" : service ? `Add ${serviceLabel(service.id)}` : "Add"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
