import { ArrowRightIcon } from "lucide-react";
import { ErrorAlert, PageHeader } from "@/components/common";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useGantry } from "@/lib/gantry";
import { href } from "@/lib/router";
import { ServiceIcon, TOOL_LABELS, serviceLabel, serviceTool, serviceUiName } from "@/lib/services";

export function ServicesPage() {
  const { catalog, appsError } = useGantry();
  return (
    <>
      <PageHeader title="Services" description="Everything an app can run locally. Add one to an app from the app's page." />
      <div className="flex flex-col gap-6">
        <ErrorAlert message={appsError} />
        <div className="grid content-start gap-4 pr-1 md:grid-cols-2 xl:grid-cols-3">
          {catalog === undefined && [0, 1, 2].map((i) => <Skeleton key={i} className="h-44 rounded-xl" />)}
          {catalog?.services.map((s) => {
            const tool = serviceTool(s.id);
            return (
              <Card key={s.id} size="sm" className="relative transition-colors hover:ring-foreground/25">
                <CardHeader>
                  <div className="flex items-center gap-3">
                    <div className="flex size-9 shrink-0 items-center justify-center rounded-lg border bg-muted/40">
                      <ServiceIcon id={s.id} className="size-4" />
                    </div>
                    <div className="min-w-0">
                      <CardTitle>
                        <a href={href.service(s.id)} className="after:absolute after:inset-0">
                          {serviceLabel(s.id)}
                        </a>
                      </CardTitle>
                      <div className="flex flex-wrap gap-1 pt-0.5">
                        <Badge variant="outline" className="font-mono font-normal">
                          {s.id}
                        </Badge>
                      </div>
                    </div>
                  </div>
                </CardHeader>
                <CardContent className="flex flex-1 flex-col gap-2">
                  <CardDescription className="line-clamp-3 text-xs">{s.description}</CardDescription>
                </CardContent>
                <CardFooter className="flex flex-wrap items-center gap-x-4 gap-y-1 border-t bg-muted/30 py-2 text-xs text-muted-foreground">
                  <span className="font-mono">
                    {Object.entries(s.ports)
                      .map(([name, port]) => `${name} :${port}`)
                      .join("  ") || "no ports"}
                  </span>
                  <span>
                    {tool
                      ? `${TOOL_LABELS[tool]} explorer`
                      : s.ui === "external"
                        ? (serviceUiName(s.id) ?? "own web UI")
                        : s.ui
                          ? `via ${serviceLabel(s.ui)}`
                          : "no UI"}
                  </span>
                  <ArrowRightIcon className="ml-auto size-3.5" />
                </CardFooter>
              </Card>
            );
          })}
        </div>
      </div>
    </>
  );
}
