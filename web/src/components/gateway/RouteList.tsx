import { memo, useDeferredValue, useEffect, useMemo, useState } from "react";
import { ArrowDownIcon, ArrowUpIcon, ChevronDownIcon, ChevronRightIcon, CopyIcon, PlusIcon, SearchIcon, Trash2Icon } from "lucide-react";
import { IconButton } from "@/components/common";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group";
import { cn } from "@/lib/utils";
import { CheckboxLabel, Code, PairRows, SelectField, SmallSelect, TextField } from "./FormBits";
import { COMMON_METHODS, refName, type Access, type FormAction, type FormRewrite, type FormRoute, type Pair, type Ref, type RewriteKind } from "./gatewayForm";

export const ACCESS_OPTIONS = [
  { value: "public", label: "Public" },
  { value: "login", label: "Login" },
  { value: "deny", label: "Blocked" },
];

/** An authorizer or step as the form knows it. */
export interface Named {
  id: number;
  name: string;
}

/** A Ref as a select value: "#<id>" for one the form defines, "=<name>" for a name it does not. */
const refValue = (ref: Ref) => (typeof ref === "number" ? `#${ref}` : `=${ref}`);
const parseRef = (value: string): Ref => (value.startsWith("#") ? Number(value.slice(1)) : value.slice(1));
const isDefined = (ref: Ref, named: Named[]) => typeof ref === "number" && named.some((n) => n.id === ref);
/** How a ref reads on screen: its current name, or the kept name marked as not defined. */
export const refLabel = (ref: Ref, named: Named[]) =>
  isDefined(ref, named) ? refName(ref, named) || "(no name)" : `${refName(ref, named) || "?"} (not defined)`;

const REWRITE_OPTIONS = [
  { value: "none", label: "None" },
  { value: "addPrefix", label: "Add prefix" },
  { value: "stripPrefix", label: "Strip prefix" },
  { value: "replace", label: "Replace path" },
];

const REWRITE_HELP: Record<RewriteKind, string> = {
  none: "The path goes to your app as it came.",
  addPrefix: "Put this in front of the path, e.g. /api turns /users into /api/users.",
  stripPrefix: "Remove this from the start of the path, e.g. /portal turns /portal/users into /users.",
  replace: "Send every matching request to this one path.",
};

/** Access select, and for Login the authorizer and user lookup steps; shared by routes and the default action. */
export function AccessFields({
  value,
  authorizers,
  steps,
  onChange,
}: {
  value: FormAction;
  authorizers: Named[];
  steps: Named[];
  onChange: (patch: Partial<FormAction>) => void;
}) {
  const options = authorizers.map((a) => ({ value: refValue(a.id), label: a.name || "(no name)" }));
  if (value.access === "login" && value.authorizer !== "" && !isDefined(value.authorizer, authorizers)) {
    options.push({ value: refValue(value.authorizer), label: refLabel(value.authorizer, authorizers) });
  }
  const stepRefs: Ref[] = [...steps.map((s) => s.id), ...value.steps.filter((r) => !isDefined(r, steps))];
  return (
    <>
      <div className="grid gap-4 sm:grid-cols-2">
        <SelectField
          label="Access"
          value={value.access}
          options={ACCESS_OPTIONS}
          help="Public: anyone. Login: a valid token is needed. Blocked: always refused."
          onChange={(a) =>
            onChange(a === "login" ? { access: "login", authorizer: authorizers[0]?.id ?? "" } : { access: a as Access, authorizer: "", steps: [] })
          }
        />
        {value.access === "login" && (
          <Field>
            <FieldLabel>Authorizer</FieldLabel>
            {options.length === 0 ? (
              <p className="text-xs text-muted-foreground">Add an authorizer below first.</p>
            ) : (
              <SmallSelect
                ariaLabel="Authorizer"
                value={value.authorizer === "" ? "" : refValue(value.authorizer)}
                options={options}
                onChange={(v) => onChange({ authorizer: parseRef(v) })}
              />
            )}
            <FieldDescription className="text-xs">Checks the caller's token.</FieldDescription>
          </Field>
        )}
      </div>
      {value.access === "login" && (
        <Field>
          <FieldLabel>User lookup</FieldLabel>
          {stepRefs.length === 0 ? (
            <p className="text-xs text-muted-foreground">No user lookup steps yet; add one below if your app needs its own user ID.</p>
          ) : (
            <div className="flex flex-wrap gap-x-5 gap-y-2">
              {stepRefs.map((ref) => (
                <CheckboxLabel
                  key={refValue(ref)}
                  checked={value.steps.includes(ref)}
                  onChange={(on) => onChange({ steps: on ? [...value.steps, ref] : value.steps.filter((x) => x !== ref) })}
                >
                  <span className="font-mono text-xs">{refLabel(ref, steps)}</span>
                </CheckboxLabel>
              ))}
            </div>
          )}
          <FieldDescription className="text-xs">Steps run after the login check, in order, e.g. to find the user in your app.</FieldDescription>
        </Field>
      )}
    </>
  );
}

interface RowProps {
  route: FormRoute;
  index: number;
  total: number;
  expanded: boolean;
  /** Reordering is off while the list is filtered. */
  filtering: boolean;
  authorizers: Named[];
  steps: Named[];
  onToggle: (id: number) => void;
  onChange: (id: number, patch: Partial<FormRoute>) => void;
  onMove: (id: number, by: -1 | 1) => void;
  onDuplicate: (id: number) => void;
  onDelete: (id: number) => void;
}

/** One route: a compact summary row that expands to all of its fields. Memoised so 100+ routes stay quick. */
const RouteRow = memo(function RouteRow({
  route,
  index,
  total,
  expanded,
  filtering,
  authorizers,
  steps,
  onToggle,
  onChange,
  onMove,
  onDuplicate,
  onDelete,
}: RowProps) {
  const access = route.access;
  const set = (patch: Partial<FormRoute>) => onChange(route.id, patch);
  const reorderHint = filtering ? "Clear the filter to reorder" : undefined;
  return (
    <div className="scroll-mt-40 rounded-lg border" data-route={route.name} data-route-id={route.id}>
      <div className="flex items-center gap-2 px-2 py-1.5">
        <button
          type="button"
          aria-expanded={expanded}
          onClick={() => onToggle(route.id)}
          className="flex min-w-0 flex-1 items-center gap-2 rounded-md px-1 py-1 text-left hover:bg-muted/60 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
        >
          {expanded ? <ChevronDownIcon className="size-4 shrink-0 text-muted-foreground" /> : <ChevronRightIcon className="size-4 shrink-0 text-muted-foreground" />}
          <span className="w-6 shrink-0 text-right text-xs text-muted-foreground tabular-nums">{index + 1}</span>
          <span className="truncate font-mono text-xs font-medium">{route.path || "/**"}</span>
          <span className="hidden truncate text-xs text-muted-foreground sm:inline">{route.name || "(no name)"}</span>
          <span className="ml-auto flex shrink-0 items-center gap-1.5">
            {route.methods.length > 0 && (
              <Badge variant="outline" className="font-mono font-normal">
                {route.methods.join(" ")}
              </Badge>
            )}
            <Badge variant={access === "deny" ? "destructive" : access === "login" ? "secondary" : "outline"} className="font-normal">
              {access === "public" ? "Public" : access === "deny" ? "Blocked" : `Login · ${route.authorizer === "" ? "?" : refLabel(route.authorizer, authorizers)}`}
            </Badge>
            {route.steps.length > 0 && (
              <Badge variant="outline" className="font-normal">
                Lookup · {route.steps.map((r) => refLabel(r, steps)).join(", ")}
              </Badge>
            )}
            {route.upstream && (
              <Badge variant="outline" className="hidden max-w-56 truncate font-mono font-normal md:inline-flex">
                → {route.upstream}
              </Badge>
            )}
          </span>
        </button>
        <div className="flex shrink-0 items-center gap-1">
          <IconButton label="Move up" tooltip={reorderHint} variant="ghost" disabled={filtering || index === 0} onClick={() => onMove(route.id, -1)}>
            <ArrowUpIcon />
          </IconButton>
          <IconButton label="Move down" tooltip={reorderHint} variant="ghost" disabled={filtering || index === total - 1} onClick={() => onMove(route.id, 1)}>
            <ArrowDownIcon />
          </IconButton>
          <IconButton label="Duplicate" variant="ghost" onClick={() => onDuplicate(route.id)}>
            <CopyIcon />
          </IconButton>
          <IconButton label="Delete" variant="ghost" onClick={() => onDelete(route.id)}>
            <Trash2Icon />
          </IconButton>
        </div>
      </div>
      {expanded && (
        <div className="flex flex-col gap-4 border-t px-4 py-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <TextField label="Name" value={route.name} onChange={(name) => set({ name })} help="Letters, numbers, '.', '_' and '-'." />
            <TextField
              label="Path"
              value={route.path}
              placeholder="/**"
              onChange={(path) => set({ path })}
              help={<>e.g. <Code>/portal/**</Code>. <Code>*</Code> is one path segment, <Code>**</Code> is anything.</>}
            />
            <TextField label="Host" value={route.host} placeholder="any host" onChange={(host) => set({ host })} help="Optional, e.g. api.example.com." />
            <TextField
              label="Send to"
              value={route.upstream}
              placeholder="your app"
              onChange={(upstream) => set({ upstream })}
              help="Optional. Blank sends it to your app; or another base URL, e.g. http://localhost:8091."
            />
          </div>
          <Methods methods={route.methods} onChange={(methods) => set({ methods })} />
          <AccessFields value={route} authorizers={authorizers} steps={steps} onChange={set} />
          <RewriteField rewrite={route.rewrite} onChange={(rewrite) => set({ rewrite })} />
          <Field>
            <FieldLabel>Extra headers for this route</FieldLabel>
            <FieldDescription className="text-xs">Added to the headers for all routes; one with the same name replaces the shared one.</FieldDescription>
            <PairRows rows={route.headers} insert onChange={(headers: Pair[]) => set({ headers })} />
          </Field>
        </div>
      )}
    </div>
  );
});

function Methods({ methods, onChange }: { methods: string[]; onChange: (methods: string[]) => void }) {
  const upper = methods.map((m) => m.toUpperCase());
  const all = [...COMMON_METHODS, ...upper.filter((m) => !COMMON_METHODS.includes(m))];
  return (
    <Field>
      <FieldLabel>Methods</FieldLabel>
      <div className="flex flex-wrap gap-x-5 gap-y-2">
        {all.map((m) => (
          <CheckboxLabel
            key={m}
            checked={upper.includes(m)}
            onChange={(on) => onChange(on ? [...methods, m] : methods.filter((x) => x.toUpperCase() !== m))}
          >
            <span className="font-mono text-xs">{m}</span>
          </CheckboxLabel>
        ))}
      </div>
      <FieldDescription className="text-xs">None ticked means all methods.</FieldDescription>
    </Field>
  );
}

function RewriteField({ rewrite, onChange }: { rewrite: FormRewrite; onChange: (rewrite: FormRewrite) => void }) {
  const kind = rewrite.kind;
  const value = kind === "none" ? "" : (rewrite[kind] ?? "");
  return (
    <Field>
      <FieldLabel>Rewrite</FieldLabel>
      <div className="flex gap-2">
        <SmallSelect
          className="w-40"
          ariaLabel="Rewrite"
          value={kind}
          options={REWRITE_OPTIONS}
          onChange={(k) => {
            const next: FormRewrite = { kind: k as RewriteKind, addPrefix: null, stripPrefix: null, replace: null, empty: false };
            if (k !== "none") next[k as Exclude<RewriteKind, "none">] = value;
            onChange(next);
          }}
        />
        {kind !== "none" && (
          <Input
            aria-label="Rewrite path"
            value={value}
            placeholder="/"
            spellCheck={false}
            className="flex-1 font-mono text-xs md:text-xs"
            onChange={(e) => onChange({ ...rewrite, [kind]: e.target.value })}
          />
        )}
      </div>
      <FieldDescription className="text-xs">{REWRITE_HELP[kind]}</FieldDescription>
    </Field>
  );
}

/** The ordered routes with a filter; rows are collapsed until opened. */
export function RouteList({
  routes,
  authorizers,
  steps,
  onChange,
  onMove,
  onDuplicate,
  onDelete,
  onAdd,
}: {
  routes: FormRoute[];
  authorizers: Named[];
  steps: Named[];
  onChange: (id: number, patch: Partial<FormRoute>) => void;
  onMove: (id: number, by: -1 | 1) => void;
  onDuplicate: (id: number) => void;
  onDelete: (id: number) => void;
  /** Returns the new route's id, which opens. */
  onAdd: () => number;
}) {
  const [open, setOpen] = useState<ReadonlySet<number>>(new Set());
  const [query, setQuery] = useState("");
  const q = useDeferredValue(query.trim().toLowerCase());
  // A new route opens and is scrolled to once it has rendered.
  const [added, setAdded] = useState<number>();
  useEffect(() => {
    if (added === undefined) return;
    document.querySelector(`[data-route-id="${added}"]`)?.scrollIntoView({ block: "start", behavior: "smooth" });
    setAdded(undefined);
  }, [added]);
  const toggle = useMemo(
    () => (id: number) =>
      setOpen((prev) => {
        const next = new Set(prev);
        if (!next.delete(id)) next.add(id);
        return next;
      }),
    [],
  );
  const shown = useMemo(
    () =>
      routes
        .map((route, index) => ({ route, index }))
        .filter(({ route }) => !q || route.path.toLowerCase().includes(q) || route.name.toLowerCase().includes(q)),
    [routes, q],
  );

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <InputGroup className="max-w-sm">
          <InputGroupAddon>
            <SearchIcon />
          </InputGroupAddon>
          <InputGroupInput aria-label="Filter routes" placeholder="Filter by path or name" value={query} onChange={(e) => setQuery(e.target.value)} />
        </InputGroup>
        <span className="text-xs text-muted-foreground">
          {q ? `${shown.length} of ${routes.length} routes` : `${routes.length} route${routes.length === 1 ? "" : "s"}`}
        </span>
        {open.size > 0 && (
          <Button variant="ghost" size="sm" onClick={() => setOpen(new Set())}>
            Collapse all
          </Button>
        )}
      </div>
      <div className={cn("flex flex-col gap-1.5")}>
        {shown.map(({ route, index }) => (
          <RouteRow
            key={route.id}
            route={route}
            index={index}
            total={routes.length}
            expanded={open.has(route.id)}
            filtering={!!q}
            authorizers={authorizers}
            steps={steps}
            onToggle={toggle}
            onChange={onChange}
            onMove={onMove}
            onDuplicate={onDuplicate}
            onDelete={onDelete}
          />
        ))}
        {routes.length > 0 && shown.length === 0 && <p className="py-2 text-xs text-muted-foreground">No route matches the filter.</p>}
        {routes.length === 0 && <p className="py-2 text-xs text-muted-foreground">No routes: every request is handled by the rule below.</p>}
      </div>
      <div>
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            const id = onAdd();
            setQuery("");
            setOpen((prev) => new Set(prev).add(id));
            setAdded(id);
          }}
        >
          <PlusIcon />
          Add route
        </Button>
      </div>
    </div>
  );
}
