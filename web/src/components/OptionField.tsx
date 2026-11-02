import { FolderIcon } from "lucide-react";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { Switch } from "@/components/ui/switch";
import type { OptionMeta } from "@/types";

interface Props {
  name: string;
  meta?: OptionMeta;
  defaultValue: string;
  /** Current override; undefined means "use the default". */
  value: string | undefined;
  onChange: (value: string) => void;
  /** Keeps ids unique when several services' options share a page. */
  idPrefix?: string;
}

/** One service option: a switch for booleans, a path box for paths, a text box otherwise, with optional help. */
export function OptionField({ name, meta, defaultValue, value, onChange, idPrefix = "option" }: Props) {
  const id = `${idPrefix}-${name}`;
  const help = meta?.help ? <FieldDescription className="text-xs">{meta.help}</FieldDescription> : null;

  if (meta?.type === "boolean") {
    return (
      <Field orientation="horizontal">
        <FieldContent>
          <FieldLabel htmlFor={id} className="font-mono text-xs">
            {name}
          </FieldLabel>
          {help}
        </FieldContent>
        <Switch id={id} checked={(value ?? defaultValue) === "true"} onCheckedChange={(checked) => onChange(String(checked))} />
      </Field>
    );
  }

  return (
    <Field>
      <FieldLabel htmlFor={id} className="font-mono text-xs">
        {name}
      </FieldLabel>
      {meta?.type === "path" ? (
        <InputGroup>
          <InputGroupAddon>
            <FolderIcon />
          </InputGroupAddon>
          <InputGroupInput
            id={id}
            className="font-mono text-xs md:text-xs"
            value={value ?? ""}
            placeholder={defaultValue || "(none)"}
            spellCheck={false}
            onChange={(e) => onChange(e.target.value)}
          />
        </InputGroup>
      ) : (
        <Input id={id} value={value ?? ""} placeholder={defaultValue || "(none)"} spellCheck={false} onChange={(e) => onChange(e.target.value)} />
      )}
      {help}
    </Field>
  );
}
