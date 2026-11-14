import { memo, useCallback, useMemo, useState, type Dispatch, type ReactNode, type SetStateAction } from "react";
import { ChevronDownIcon, ChevronRightIcon, PlusIcon, Trash2Icon } from "lucide-react";
import { api } from "@/api";
import { IconButton } from "@/components/common";
import { Button } from "@/components/ui/button";
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Field, FieldContent, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { useLoad } from "@/hooks";
import { CheckboxLabel, Code, FailureField, ListField, ListRows, PairRows, SelectField, SmallSelect, SubHeading, TextField } from "./FormBits";
import {
  AUTHORIZER_TYPES,
  DEFAULT_REDACT,
  DEFAULT_STRIP,
  duplicateRoute,
  newAuthorizer,
  newRoute,
  newStep,
  items,
  nameProblem,
  removeNamed,
  tokenSource,
  type DefaultableList,
  type FormAuthorizer,
  type FormCall,
  type FormRoute,
  type FormState,
  type FormStep,
  type Pair,
} from "./gatewayForm";
import { AccessFields, RouteList, type Named } from "./RouteList";

type SetForm = Dispatch<SetStateAction<FormState>>;

/** The "nothing chosen" value of a select, which cannot use an empty string. */
const NONE = "\u0000none";

const HTTP_METHOD_OPTIONS = ["GET", "POST", "PUT", "PATCH", "DELETE"].map((m) => ({ value: m, label: m }));

/** A titled card for one part of the form. */
function Section({ title, description, action, children }: { title: string; description?: ReactNode; action?: ReactNode; children: ReactNode }) {
  return (
    <Card size="sm">
      <CardHeader>
        <CardTitle>{title}</CardTitle>
        {description && <CardDescription>{description}</CardDescription>}
        {action && <CardAction>{action}</CardAction>}
      </CardHeader>
      <CardContent className="flex flex-col gap-4">{children}</CardContent>
    </Card>
  );
}

/** A closed-by-default group of rarely changed fields. */
function More({ title, children }: { title: string; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <CollapsibleTrigger render={<Button variant="ghost" size="sm" className="-ml-2 text-muted-foreground" />}>
        {open ? <ChevronDownIcon /> : <ChevronRightIcon />}
        {title}
      </CollapsibleTrigger>
      <CollapsibleContent className="flex flex-col gap-4 pt-3">{children}</CollapsibleContent>
    </Collapsible>
  );
}

/**
 * The authorizers or steps as id and name, a new array only when one of those changes, so the memoised route rows
 * re-render on a rename but not on every keystroke elsewhere in an authorizer or step.
 */
function useNamed(list: { id: number; name: string }[]): Named[] {
  const key = list.map((i) => `${i.id}:${i.name}`).join("\u0000");
  // eslint-disable-next-line react-hooks/exhaustive-deps
  return useMemo(() => list.map(({ id, name }) => ({ id, name })), [key]);
}

/** Every part of gateway.yml as plain fields. The parent owns the state, the dirty check and Save. */
export function ConfigForm({ app, form, setForm }: { app: string; form: FormState; setForm: SetForm }) {
  const authorizers = useNamed(form.authorizers);
  const steps = useNamed(form.steps);

  const changeRoute = useCallback(
    (id: number, patch: Partial<FormRoute>) => setForm((s) => ({ ...s, routes: s.routes.map((r) => (r.id === id ? { ...r, ...patch } : r)) })),
    [setForm],
  );
  const moveRoute = useCallback(
    (id: number, by: -1 | 1) =>
      setForm((s) => {
        const i = s.routes.findIndex((r) => r.id === id);
        const j = i + by;
        if (i < 0 || j < 0 || j >= s.routes.length) return s;
        const routes = [...s.routes];
        [routes[i], routes[j]] = [routes[j], routes[i]];
        return { ...s, routes };
      }),
    [setForm],
  );
  const duplicate = useCallback(
    (id: number) =>
      setForm((s) => {
        const i = s.routes.findIndex((r) => r.id === id);
        if (i < 0) return s;
        const routes = [...s.routes];
        routes.splice(i + 1, 0, duplicateRoute(s.routes[i], s.routes));
        return { ...s, routes };
      }),
    [setForm],
  );
  const deleteRoute = useCallback((id: number) => setForm((s) => ({ ...s, routes: s.routes.filter((r) => r.id !== id) })), [setForm]);
  const addRoute = useCallback(() => {
    const route = newRoute();
    setForm((s) => ({ ...s, routes: [...s.routes, route] }));
    return route.id;
  }, [setForm]);

  return (
    <div className="flex flex-col gap-4">
      <Section title="Upstream">
        <TextField
          label="Your app's URL"
          value={form.upstream}
          placeholder="http://localhost:8080"
          className="max-w-md"
          onChange={(upstream) => setForm((s) => ({ ...s, upstream }))}
          help="Where requests go. Blank uses the gateway service's upstream setting."
        />
      </Section>

      <SharedHeaders headers={form.headers} setForm={setForm} />

      <Section title="Routes" description="Checked from the top; the first route that matches a request handles it.">
        <RouteList
          routes={form.routes}
          authorizers={authorizers}
          steps={steps}
          onChange={changeRoute}
          onMove={moveRoute}
          onDuplicate={duplicate}
          onDelete={deleteRoute}
          onAdd={addRoute}
        />
      </Section>

      <Section title="Requests that match no route">
        <AccessFields
          value={form.default}
          authorizers={authorizers}
          steps={steps}
          onChange={(patch) => setForm((s) => ({ ...s, default: { ...s.default, ...patch } }))}
        />
      </Section>

      <Section title="WebSocket connections">
        <AccessFields
          value={form.websocket}
          authorizers={authorizers}
          steps={steps}
          onChange={(patch) => setForm((s) => ({ ...s, websocket: { ...s.websocket, ...patch } }))}
        />
        <TextField
          label="Connect hook"
          value={form.websocket.connect}
          placeholder="{{upstream}}/realtime/connect/{{connectionId}}"
          onChange={(connect) => setForm((s) => ({ ...s, websocket: { ...s.websocket, connect } }))}
          help="The $connect integration: POSTed the handshake's headers when a socket opens; a non-2xx answer refuses it. Blank: none."
        />
        <TextField
          label="Disconnect hook"
          value={form.websocket.disconnect}
          placeholder="{{upstream}}/realtime/disconnect/{{connectionId}}"
          onChange={(disconnect) => setForm((s) => ({ ...s, websocket: { ...s.websocket, disconnect } }))}
          help="The $disconnect integration: called with DELETE when a socket closes. Message routes are edited in YAML."
        />
      </Section>

      <Authorizers app={app} authorizers={form.authorizers} setForm={setForm} />
      <Steps steps={form.steps} setForm={setForm} />
      <CorsSection form={form} setForm={setForm} />
      <AdvancedSection form={form} setForm={setForm} />
    </div>
  );
}

const SharedHeaders = memo(function SharedHeaders({ headers, setForm }: { headers: Pair[]; setForm: SetForm }) {
  return (
    <Section title="Headers your app receives (all routes)" description="Set on every request the gateway passes to your app.">
      <PairRows rows={headers} insert onChange={(rows: Pair[]) => setForm((s) => ({ ...s, headers: rows }))} />
      <div className="flex flex-col gap-1.5 rounded-lg border bg-muted/40 p-3 text-xs text-muted-foreground">
        <p>
          Values can use <Code>{"{{userId}}"}</Code>, <Code>{"{{email}}"}</Code>, <Code>{"{{sub}}"}</Code> and other token claims,{" "}
          <Code>{"{{errorCode}}"}</Code>, and <Code>{"{{secret:NAME}}"}</Code>. Pick one from the <Code>{"{ }"}</Code> button next to a value.
        </p>
        <p>
          On routes that need a login, your app always gets the identity headers (listed under Advanced, "Headers removed from
          incoming requests"), plus these and the route's own. Values come from the login; one with nothing to fill it arrives blank.
        </p>
        <p>On public routes these headers are sent too, but a value that needs a login arrives blank.</p>
      </div>
    </Section>
  );
});

const Authorizers = memo(function Authorizers({ app, authorizers, setForm }: { app: string; authorizers: FormAuthorizer[]; setForm: SetForm }) {
  const pushed = useLoad(() => api.gatewayAuthorizers(app), [app]).data;
  const change = (id: number, patch: Partial<FormAuthorizer>) =>
    setForm((s) => ({ ...s, authorizers: s.authorizers.map((a) => (a.id === id ? { ...a, ...patch } : a)) }));
  return (
    <Section
      title="Authorizers"
      description="Ways a caller can log in. A route set to Login picks one of these."
      action={
        <Button variant="outline" size="sm" onClick={() => setForm((s) => ({ ...s, authorizers: [...s.authorizers, newAuthorizer()] }))}>
          <PlusIcon />
          Add authorizer
        </Button>
      }
    >
      {authorizers.length === 0 && <p className="text-xs text-muted-foreground">None yet. Without one, routes can only be Public or Blocked.</p>}
      {authorizers.map((a) => (
        <AuthorizerCard
          key={a.id}
          a={a}
          nameError={nameProblem(
            a.name,
            authorizers.filter((x) => x.id !== a.id).map((x) => x.name),
            "authorizer",
          )}
          pushed={pushed?.map((p) => p.name) ?? []}
          onChange={(patch) => change(a.id, patch)}
          onDelete={() => setForm((s) => removeNamed(s, "authorizer", a.id))}
        />
      ))}
    </Section>
  );
});

function AuthorizerCard({
  a,
  nameError,
  pushed,
  onChange,
  onDelete,
}: {
  a: FormAuthorizer;
  nameError?: string;
  pushed: string[];
  onChange: (patch: Partial<FormAuthorizer>) => void;
  onDelete: () => void;
}) {
  const token = tokenSource(a.tokenFrom);
  const containers = [...pushed, ...(a.container && !pushed.includes(a.container) ? [a.container] : [])];
  const list = (key: "clientIds" | "audiences" | "algorithms") => (rows: FormAuthorizer[typeof key]) => onChange({ [key]: rows });
  return (
    <div className="flex flex-col gap-4 rounded-lg border p-4">
      <div className="flex items-start gap-2">
        <div className="grid flex-1 gap-4 sm:grid-cols-2">
          <TextField label="Name" value={a.name} error={nameError} onChange={(name) => onChange({ name })} help="Routes that use it follow a rename." />
          <SelectField label="Type" value={a.type} options={AUTHORIZER_TYPES} onChange={(type) => onChange({ type })} />
        </div>
        <IconButton label="Delete authorizer" variant="ghost" onClick={onDelete}>
          <Trash2Icon />
        </IconButton>
      </div>

      {a.type === "firebase" && (
        <TextField label="Project ID" value={a.projectId} placeholder="my-firebase-project" onChange={(projectId) => onChange({ projectId })} />
      )}
      {a.type === "cognito" && (
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField label="Region" value={a.region} placeholder="us-east-1" onChange={(region) => onChange({ region })} />
          <TextField label="User pool ID" value={a.userPoolId} onChange={(userPoolId) => onChange({ userPoolId })} />
          <ListField label="Client IDs" rows={a.clientIds} addLabel="Add client ID" onChange={list("clientIds")} />
          <SelectField
            label="Token use"
            value={a.tokenUse}
            options={[
              { value: "id", label: "ID token" },
              { value: "access", label: "Access token" },
            ]}
            onChange={(tokenUse) => onChange({ tokenUse })}
          />
        </div>
      )}
      {a.type === "supabase" && (
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField label="Project URL" value={a.url} placeholder="https://xyz.supabase.co" onChange={(url) => onChange({ url })} />
          <TextField
            label="JWT secret"
            value={a.jwtSecret}
            placeholder="{{secret:SUPABASE_JWT_SECRET}}"
            onChange={(jwtSecret) => onChange({ jwtSecret })}
            help={<>Only for a legacy project. Use <Code>{"{{secret:NAME}}"}</Code>, never the secret itself.</>}
          />
        </div>
      )}
      {a.type === "jwt" && (
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField
            label="JWKS URL"
            value={a.jwksUrl}
            placeholder="https://issuer.example.com/.well-known/jwks.json"
            onChange={(jwksUrl) => onChange({ jwksUrl })}
            help={a.jwks ? "This authorizer also has an inline key set, kept as it is (edit it as YAML)." : undefined}
          />
          <TextField label="Issuer" value={a.issuer} onChange={(issuer) => onChange({ issuer })} help="Optional." />
          <ListField label="Audiences" rows={a.audiences} addLabel="Add audience" onChange={list("audiences")} help="Optional." />
          <ListField label="Algorithms" rows={a.algorithms} placeholder="RS256" addLabel="Add algorithm" onChange={list("algorithms")} help="Usually RS256." />
        </div>
      )}
      {a.type === "custom" && (
        <>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field>
              <FieldLabel>Pushed authorizer</FieldLabel>
              <SmallSelect
                ariaLabel="Pushed authorizer"
                value={a.container === "" ? NONE : a.container}
                options={[{ value: NONE, label: "None" }, ...containers.map((c) => ({ value: c, label: pushed.includes(c) ? c : `${c} (not pushed)` }))]}
                onChange={(container) => onChange({ container: container === NONE ? "" : container })}
              />
              <FieldDescription className="text-xs">One you pushed (see the Authorizers tab), or leave None and give a URL.</FieldDescription>
            </Field>
            <TextField label="URL" value={a.url} placeholder="http://localhost:9000/check" onChange={(url) => onChange({ url })} help="Instead of a pushed authorizer." />
          </div>
          <Field>
            <FieldLabel>Environment</FieldLabel>
            <PairRows
              rows={a.env}
              namePlaceholder="Name"
              addLabel="Add variable"
              onChange={(env: Pair[]) => onChange({ env })}
            />
            <FieldDescription className="text-xs">Use <Code>{"{{secret:NAME}}"}</Code> for secret values.</FieldDescription>
          </Field>
        </>
      )}

      <More title="Advanced">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field>
            <FieldLabel>Token from</FieldLabel>
            <div className="flex gap-2">
              <SmallSelect
                className="w-40"
                ariaLabel="Token from"
                value={token.kind}
                options={[
                  { value: "header", label: "Header" },
                  { value: "query", label: "Query parameter" },
                ]}
                onChange={(kind) => onChange({ tokenFrom: `${kind}:${kind === "header" ? "Authorization" : "token"}` })}
              />
              <Input
                aria-label="Token name"
                value={token.name}
                spellCheck={false}
                className="flex-1 font-mono text-xs md:text-xs"
                onChange={(e) => onChange({ tokenFrom: `${token.kind}:${e.target.value}` })}
              />
            </div>
            <FieldDescription className="text-xs">Usually the Authorization header.</FieldDescription>
          </Field>
          <FailureField
            label="Unverified email"
            value={a.onUnverifiedEmail}
            continueLabel="Allow and flag with code"
            onChange={(onUnverifiedEmail) => onChange({ onUnverifiedEmail })}
            help={<>Flagging lets the request through and sets <Code>{"{{errorCode}}"}</Code>.</>}
          />
          <TextField label="Deny status" value={a.denyStatus} onChange={(denyStatus) => onChange({ denyStatus })} help="400–599." />
          <TextField
            label="Missing-token status"
            value={a.missingTokenStatus}
            placeholder={a.denyStatus || "403"}
            onChange={(missingTokenStatus) => onChange({ missingTokenStatus })}
            help="When no token is sent. Blank uses the deny status."
          />
        </div>
      </More>
    </div>
  );
}

const Steps = memo(function Steps({ steps, setForm }: { steps: FormStep[]; setForm: SetForm }) {
  const change = (id: number, patch: Partial<FormStep>) =>
    setForm((s) => ({ ...s, steps: s.steps.map((x) => (x.id === id ? { ...x, ...patch } : x)) }));
  return (
    <Section
      title="User lookup steps"
      description="Calls to your app after a login, e.g. to turn the token's email into your own user ID."
      action={
        <Button variant="outline" size="sm" onClick={() => setForm((s) => ({ ...s, steps: [...s.steps, newStep()] }))}>
          <PlusIcon />
          Add step
        </Button>
      }
    >
      {steps.length === 0 && <p className="text-xs text-muted-foreground">None yet.</p>}
      {steps.map((st) => (
        <StepCard
          key={st.id}
          st={st}
          nameError={nameProblem(
            st.name,
            steps.filter((x) => x.id !== st.id).map((x) => x.name),
            "step",
          )}
          onChange={(patch) => change(st.id, patch)}
          onDelete={() => setForm((s) => removeNamed(s, "step", st.id))}
        />
      ))}
    </Section>
  );
});

function CallFields({ call, onChange, body }: { call: FormCall; onChange: (call: FormCall) => void; body: boolean }) {
  return (
    <>
      <div className="flex gap-2">
        <SmallSelect
          className="w-28"
          ariaLabel="Method"
          value={call.method}
          options={HTTP_METHOD_OPTIONS.some((o) => o.value === call.method) ? HTTP_METHOD_OPTIONS : [...HTTP_METHOD_OPTIONS, { value: call.method, label: call.method }]}
          onChange={(method) => onChange({ ...call, method })}
        />
        <Input
          aria-label="URL"
          value={call.url}
          placeholder="{{upstream}}/internal/users/by-email"
          spellCheck={false}
          className="flex-1 font-mono text-xs md:text-xs"
          onChange={(e) => onChange({ ...call, url: e.target.value })}
        />
      </div>
      <Field>
        <FieldLabel>Headers</FieldLabel>
        <PairRows rows={call.headers} insert onChange={(headers: Pair[]) => onChange({ ...call, headers })} />
      </Field>
      {(body || call.body !== null) && (
        <Field>
          <FieldLabel>Body fields</FieldLabel>
          <PairRows
            rows={call.body ?? []}
            insert
            namePlaceholder="Field"
            addLabel="Add field"
            onChange={(rows: Pair[]) => onChange({ ...call, body: rows })}
          />
          <FieldDescription className="text-xs">Sent as a JSON object.</FieldDescription>
        </Field>
      )}
    </>
  );
}

function StepCard({ st, nameError, onChange, onDelete }: { st: FormStep; nameError?: string; onChange: (patch: Partial<FormStep>) => void; onDelete: () => void }) {
  return (
    <div className="flex flex-col gap-4 rounded-lg border p-4">
      <div className="flex items-start gap-2">
        <TextField
          label="Name"
          value={st.name}
          error={nameError}
          className="max-w-sm flex-1"
          onChange={(name) => onChange({ name })}
          help="Routes that use it follow a rename."
        />
        <div className="flex-1" />
        <IconButton label="Delete step" variant="ghost" onClick={onDelete}>
          <Trash2Icon />
        </IconButton>
      </div>
      <SubHeading title="Lookup" help={<>The call that finds the user. <Code>{"{{upstream}}"}</Code> is your app's URL; token claims such as <Code>{"{{email}}"}</Code> can be used.</>} />
      <CallFields call={st.lookup} body={false} onChange={(lookup) => onChange({ lookup })} />

      <Field orientation="horizontal">
        <FieldContent>
          <FieldLabel>Create if not found</FieldLabel>
          <FieldDescription className="text-xs">When the lookup finds no one, make the user with a second call.</FieldDescription>
        </FieldContent>
        <Switch checked={st.hasCreate} onCheckedChange={(hasCreate) => onChange({ hasCreate })} aria-label="Create if not found" />
      </Field>
      {st.hasCreate && <CallFields call={st.create} body onChange={(create) => onChange({ create })} />}

      <SubHeading title="Map" help="Names you can use in headers, each read from the response, e.g. userId from $.data.user_id." />
      <PairRows
        rows={st.map}
        namePlaceholder="Name, e.g. userId"
        valuePlaceholder="$.data.user_id"
        addLabel="Add name"
        onChange={(map: Pair[]) => onChange({ map })}
      />
      <div className="grid gap-4 sm:grid-cols-2">
        <TextField
          label="Cache seconds"
          value={st.cacheSeconds}
          onChange={(cacheSeconds) => onChange({ cacheSeconds })}
          help="How long a found user is remembered."
        />
        <FailureField
          label="On failure"
          value={st.onFailure}
          continueLabel="Continue with code"
          onChange={(onFailure) => onChange({ onFailure })}
          help={<>Continue lets the request through and sets <Code>{"{{errorCode}}"}</Code>.</>}
        />
      </div>
    </div>
  );
}

function CorsSection({ form, setForm }: { form: FormState; setForm: SetForm }) {
  const c = form.cors;
  const set = (patch: Partial<FormState["cors"]>) => setForm((s) => ({ ...s, cors: { ...s.cors, ...patch } }));
  return (
    <Section
      title="CORS"
      description="Lets web pages on other origins call the gateway from a browser."
      action={<Switch checked={form.corsOn} aria-label="CORS" onCheckedChange={(corsOn) => setForm((s) => ({ ...s, corsOn }))} />}
    >
      {form.corsOn ? (
        <div className="grid gap-4 sm:grid-cols-2">
          <ListField
            label="Origins"
            rows={c.origins}
            placeholder="http://localhost:3000"
            addLabel="Add origin"
            onChange={(origins) => set({ origins })}
            help={<>Each as scheme://host[:port]; <Code>*</Code> allows any.</>}
          />
          <TextField label="Max age (seconds)" value={c.maxAge} onChange={(maxAge) => set({ maxAge })} help="How long browsers cache the answer." />
          <ListField label="Allow headers" rows={c.allowHeaders} addLabel="Add header" onChange={(allowHeaders) => set({ allowHeaders })} help="Request headers pages may send. None listed allows any." />
          <ListField label="Expose headers" rows={c.exposeHeaders} addLabel="Add header" onChange={(exposeHeaders) => set({ exposeHeaders })} help="Response headers pages may read." />
          <CheckboxLabel checked={c.credentials} onChange={(credentials) => set({ credentials })}>
            Allow credentials (cookies and auth headers); needs origins listed, not <Code>*</Code>
          </CheckboxLabel>
        </div>
      ) : (
        <p className="text-xs text-muted-foreground">Off: browsers on other origins can't call the gateway.</p>
      )}
    </Section>
  );
}

/** A list the server has a default for: "Use the default list", or exactly the items given (none is allowed). */
function DefaultableListField({
  label,
  value,
  fallback,
  onChange,
  help,
}: {
  label: string;
  value: DefaultableList;
  fallback: string[];
  onChange: (value: DefaultableList) => void;
  help: ReactNode;
}) {
  return (
    <Field>
      <FieldLabel>{label}</FieldLabel>
      <CheckboxLabel
        checked={value.useDefault}
        // Unticking starts from the default list, ready to edit; ticking forgets the items.
        onChange={(useDefault) => onChange({ useDefault, items: items(fallback) })}
      >
        Use the default list
      </CheckboxLabel>
      {value.useDefault ? (
        <p className="font-mono text-xs text-muted-foreground">{fallback.join(", ")}</p>
      ) : (
        <>
          <ListRows rows={value.items} label={label} addLabel="Add header" onChange={(rows) => onChange({ useDefault: false, items: rows })} />
          {value.items.length === 0 && <p className="text-xs text-muted-foreground">None: the list is empty.</p>}
        </>
      )}
      <FieldDescription className="text-xs">{help}</FieldDescription>
    </Field>
  );
}

function AdvancedSection({ form, setForm }: { form: FormState; setForm: SetForm }) {
  const setLog = (patch: Partial<FormState["log"]>) => setForm((s) => ({ ...s, log: { ...s.log, ...patch } }));
  return (
    <Section title="Advanced">
      <More title="Show advanced settings">
        <DefaultableListField
          label="Headers removed from incoming requests"
          value={form.stripHeaders}
          fallback={DEFAULT_STRIP}
          onChange={(stripHeaders) => setForm((s) => ({ ...s, stripHeaders }))}
          help="Callers can never send these themselves; they are the identity headers."
        />
        <SubHeading title="Call log" />
        <div className="grid gap-4 sm:grid-cols-2">
          <TextField label="Calls kept" value={form.log.maxCalls} onChange={(maxCalls) => setLog({ maxCalls })} help="Only the newest are kept." />
          <TextField label="Body size shown (bytes)" value={form.log.maxBodyBytes} onChange={(maxBodyBytes) => setLog({ maxBodyBytes })} />
        </div>
        <DefaultableListField
          label="Hidden headers"
          value={form.log.redact}
          fallback={DEFAULT_REDACT}
          onChange={(redact) => setLog({ redact })}
          help="Their values are hidden in the call log."
        />
      </More>
    </Section>
  );
}
