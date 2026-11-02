import { useState } from "react";
import { toast } from "sonner";
import { api } from "@/api";
import { ErrorAlert } from "@/components/common";
import { OptionField } from "@/components/OptionField";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { FieldGroup } from "@/components/ui/field";
import { ScrollArea } from "@/components/ui/scroll-area";
import { useAction } from "@/hooks";
import { serviceLabel } from "@/lib/services";
import type { CatalogService, ServiceChange, ServiceStatus } from "@/types";

interface Props {
  app: string;
  service: ServiceStatus;
  catalog: CatalogService;
  onClose: () => void;
  onSaved: () => void;
}

/** Edits the options of a service already in the app. Changes take effect on the next Start. */
export function ServiceSettingsDialog({ app, service, catalog, onClose, onSaved }: Props) {
  const [values, setValues] = useState<Record<string, string>>(service.options);
  const { busy, error, run } = useAction();
  const changed = Object.fromEntries(Object.entries(values).filter(([key, value]) => value !== service.options[key]));
  const dirty = Object.keys(changed).length > 0;

  const save = async () => {
    let change: ServiceChange | undefined;
    const save = async () => (change = await api.addService(app, service.id, changed));
    if (await run("save", save)) {
      toast.success(`Saved ${serviceLabel(service.id)} settings`, {
        description: change?.applied ? "Applied to the running app." : "They apply the next time you press Start.",
      });
      change?.notes.forEach((note) => toast.info(note));
      onSaved();
      onClose();
    }
  };

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>{serviceLabel(service.id)} settings</DialogTitle>
          <DialogDescription>Saved settings apply the next time you press Start.</DialogDescription>
        </DialogHeader>
        <ScrollArea className="max-h-[60vh] pr-3">
          <FieldGroup className="gap-4">
            {catalog.optionOrder.map((key) => (
              <OptionField
                key={key}
                name={key}
                meta={catalog.optionMeta[key]}
                defaultValue={catalog.options[key] ?? ""}
                value={values[key]}
                onChange={(value) => setValues({ ...values, [key]: value })}
              />
            ))}
          </FieldGroup>
        </ScrollArea>
        <ErrorAlert message={error} />
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={!dirty || !!busy} onClick={save}>
            {busy ? "Saving…" : "Save"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
