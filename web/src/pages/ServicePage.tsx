import { LayersIcon } from "lucide-react";
import { CodeBlock, PageHeader } from "@/components/common";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";
import { useGantry } from "@/lib/gantry";
import { href } from "@/lib/router";
import { ServiceIcon, TOOL_LABELS, serviceLabel, serviceTool, serviceUiName } from "@/lib/services";
import type { CatalogService } from "@/types";

/** Where the service's UI lives, in words: built-in explorer, its own web UI, or another service's. */
function uiNote(service: CatalogService): string {
  const tool = serviceTool(service.id);
  if (tool) return `Built-in ${TOOL_LABELS[tool]} explorer in the dashboard`;
  if (service.ui === "external") {
    const name = serviceUiName(service.id);
    return name ? `${name}, started with it and linked from the app page (Open UI)` : "Has its own web UI, linked from the app page";
  }
  if (service.ui) return `Browsed through ${serviceLabel(service.ui)}`;
  return "No UI";
}

export function ServicePage({ service: id }: { service: string }) {
  const { catalog } = useGantry();
  if (catalog === undefined) return <Skeleton className="h-64 w-full rounded-xl" />;
  const service = catalog.services.find((s) => s.id === id);
  if (!service) {
    return (
      <Empty className="border">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <LayersIcon />
          </EmptyMedia>
          <EmptyTitle>Unknown service</EmptyTitle>
          <EmptyDescription>
            There is no <span className="font-mono">{id}</span> in the catalog.
          </EmptyDescription>
        </EmptyHeader>
        <EmptyContent>
          <Button variant="outline" nativeButton={false} render={<a href={href.services()} />}>
            All services
          </Button>
        </EmptyContent>
      </Empty>
    );
  }

  return (
    <>
      <PageHeader
        icon={<ServiceIcon id={service.id} className="size-5" />}
        title={serviceLabel(service.id)}
        description={
          <span className="flex flex-wrap items-center gap-1 pt-1">
            <Badge variant="outline" className="font-mono font-normal">
              {service.id}
            </Badge>
          </span>
        }
      />
      {/* Integration only: options, env and which apps use it are per app and live on the app page. */}
      <div className="flex max-w-3xl flex-col gap-6">
        <Card size="sm">
          <CardHeader>
            <CardTitle>About</CardTitle>
          </CardHeader>
          <CardContent className="text-sm">{service.description}</CardContent>
        </Card>
        <Card size="sm">
          <CardHeader>
            <CardTitle>Integration</CardTitle>
            <CardDescription>How Gantry runs it and wires it into an app.</CardDescription>
          </CardHeader>
          <CardContent>
            <dl className="grid grid-cols-[8rem_1fr] gap-x-4 gap-y-3 text-sm">
              <dt className="text-muted-foreground">Dashboard</dt>
              <dd>{uiNote(service)}</dd>
              <dt className="text-muted-foreground">Requires</dt>
              <dd>{service.requires.length ? `${service.requires.map(serviceLabel).join(", ")} (added automatically)` : "Nothing"}</dd>
              <dt className="text-muted-foreground">Host ports</dt>
              <dd>
                <span className="font-mono text-xs">
                  {Object.entries(service.ports)
                    .map(([name, port]) => `${name} :${port}`)
                    .join("  ") || "—"}
                </span>
                <span className="block text-xs text-muted-foreground">Preferred ports on 127.0.0.1; each app gets the next free one.</span>
              </dd>
              <dt className="text-muted-foreground">Add to an app</dt>
              <dd className="flex flex-col gap-1.5">
                <span>
                  On the app page, <span className="font-medium">Add service</span>; or from the terminal:
                </span>
                <CodeBlock className="m-0">{`gantry add <app> ${service.id}${service.optionOrder.length ? " [--set key=value ...]" : ""}`}</CodeBlock>
              </dd>
            </dl>
          </CardContent>
        </Card>
      </div>
    </>
  );
}
