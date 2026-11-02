import { useEffect, useState } from "react";
import { BoxIcon, ChevronRightIcon, ExternalLinkIcon, PlusIcon, UploadIcon } from "lucide-react";
import { SyncDialog } from "@/components/share/SyncDialog";
import { ImportDialog } from "@/components/share/ImportDialog";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import {
  SidebarGroup,
  SidebarGroupLabel,
  SidebarMenu,
  SidebarMenuAction,
  SidebarMenuBadge,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSkeleton,
  SidebarMenuSub,
  SidebarMenuSubButton,
  SidebarMenuSubItem,
  SidebarSeparator,
} from "@/components/ui/sidebar";
import { useGantry } from "@/lib/gantry";
import { href, type Route } from "@/lib/router";
import { ServiceIcon, TOOL_LABELS, serviceLabel, serviceTool } from "@/lib/services";
import { cn } from "@/lib/utils";
import type { AppSummary, SqlService } from "@/types";

/** Green when every service runs, amber when some do, grey when the app is stopped. */
function RunDot({ app }: { app: AppSummary }) {
  const tone = app.running === 0 ? "bg-muted-foreground/40" : app.running < app.services.length ? "bg-amber-500" : "bg-emerald-500";
  return <span className={cn("absolute -right-0.5 -bottom-0.5 size-2 rounded-full ring-2 ring-sidebar", tone)} />;
}

export function NavApps({ route, query }: { route: Route; query: string }) {
  const { apps } = useGantry();
  const currentApp = route.page === "app" || route.page === "tool" ? route.app : undefined;
  // Controlled so navigating to another app expands it without collapsing the ones the user opened.
  const [open, setOpen] = useState<Set<string>>(() => new Set(currentApp ? [currentApp] : []));
  useEffect(() => {
    if (currentApp) setOpen((prev) => (prev.has(currentApp) ? prev : new Set(prev).add(currentApp)));
  }, [currentApp]);
  const toggle = (app: string, isOpen: boolean) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (isOpen) next.add(app);
      else next.delete(app);
      return next;
    });

  const [importOpen, setImportOpen] = useState(false);
  // Databases of a just-imported app that have a source: first asked about, then synced one after another.
  const [offer, setOffer] = useState<{ app: string; services: SqlService[] }>();
  const [syncQueue, setSyncQueue] = useState<{ app: string; services: SqlService[] }>();

  const q = query.trim().toLowerCase();
  // Each app with its services listed under it. While searching, an app shows when its name matches (with all
  // services) or when some of its services match (with only those, expanded).
  const entries = (apps ?? []).flatMap((app) => {
    const services = app.services.map((id) => ({ id, tool: serviceTool(id), externalUi: app.externalUis?.[id] }));
    if (!q || app.name.toLowerCase().includes(q)) return [{ app, services, forceOpen: false }];
    const matching = services.filter(({ id, tool }) =>
      [tool ? TOOL_LABELS[tool] : "", id, serviceLabel(id)].some((text) => text.toLowerCase().includes(q)),
    );
    return matching.length > 0 ? [{ app, services: matching, forceOpen: true }] : [];
  });

  return (
    <SidebarGroup>
      <SidebarGroupLabel>Apps</SidebarGroupLabel>
      <SidebarMenu>
        {apps === undefined && [0, 1, 2].map((i) => <SidebarMenuSkeleton key={i} showIcon />)}
        {apps && q && entries.length === 0 && (
          <p className="px-2 py-1 text-xs text-sidebar-foreground/70">No apps match “{query.trim()}”.</p>
        )}
        {entries.map(({ app, services, forceOpen }) => {
          const isOpen = forceOpen || open.has(app.name);
          return (
            <Collapsible
              key={app.name}
              open={isOpen}
              onOpenChange={(isOpen) => toggle(app.name, isOpen)}
              render={<SidebarMenuItem />}
            >
              {/* Full grey on the app's own page; a lighter one while expanded, so the open section reads as one block
                  without competing with the page you're on. */}
              <SidebarMenuButton
                tooltip={app.name}
                className={cn("text-sm", isOpen && "bg-sidebar-accent/50")}
                isActive={route.page === "app" && route.app === app.name}
                // The whole row toggles: opening also goes to the app page, closing stays where you are (going to the
                // app page would open it again). While searching, matches stay open.
                onClick={(e) => {
                  if (forceOpen || services.length === 0) return;
                  if (isOpen) e.preventDefault();
                  toggle(app.name, !isOpen);
                }}
                render={<a href={href.app(app.name)} />}
              >
                <span className="relative">
                  <BoxIcon />
                  <RunDot app={app} />
                </span>
                <span>{app.name}</span>
              </SidebarMenuButton>
              <SidebarMenuBadge className={cn(services.length > 0 && "mr-6")}>
                {app.running}/{app.services.length}
              </SidebarMenuBadge>
              {services.length > 0 && (
                <>
                  <CollapsibleTrigger render={<SidebarMenuAction className="transition-transform duration-200 ease-out aria-expanded:rotate-90" />}>
                    <ChevronRightIcon />
                    <span className="sr-only">Toggle</span>
                  </CollapsibleTrigger>
                  <CollapsibleContent className="h-(--collapsible-panel-height) overflow-hidden transition-[height] duration-200 ease-out data-ending-style:h-0 data-starting-style:h-0">
                    <SidebarMenuSub>
                      {/* Explorers open in place, services with their own UI open it in a new tab, the rest the app page. */}
                      {services.map(({ id, tool, externalUi }) => (
                        <SidebarMenuSubItem key={id}>
                          <SidebarMenuSubButton
                            className="data-active:font-medium data-[size=md]:text-[13px]"
                            isActive={route.page === "tool" && route.app === app.name && route.tool === tool}
                            render={
                              tool ? (
                                <a href={href.tool(app.name, tool)} />
                              ) : externalUi ? (
                                <a href={externalUi} target="_blank" rel="noreferrer" />
                              ) : (
                                <a href={href.app(app.name)} />
                              )
                            }
                          >
                            <ServiceIcon id={id} />
                            <span className="min-w-0 flex-1 truncate">{serviceLabel(id)}</span>
                            {!tool && externalUi && (
                              <ExternalLinkIcon
                                aria-label="Opens in a new tab"
                                className="size-3.5! opacity-0 transition-opacity group-focus-within/menu-sub-item:opacity-60 group-hover/menu-sub-item:opacity-60"
                              />
                            )}
                          </SidebarMenuSubButton>
                        </SidebarMenuSubItem>
                      ))}
                    </SidebarMenuSub>
                  </CollapsibleContent>
                </>
              )}
            </Collapsible>
          );
        })}
        {/* Only when something sits above it: skeletons, apps, or the no-match note. */}
        {(apps === undefined || q || entries.length > 0) && (
          <li aria-hidden className="mt-5 mb-2.5 pr-2">
            <SidebarSeparator className="mr-4" />
          </li>
        )}
        <SidebarMenuItem>
          <SidebarMenuButton
            isActive={route.page === "new-app"}
            className="text-[13px] text-sidebar-foreground/70"
            render={<a href={href.newApp()} />}
          >
            <PlusIcon />
            <span>New app</span>
          </SidebarMenuButton>
        </SidebarMenuItem>
        <SidebarMenuItem>
          <SidebarMenuButton className="text-[13px] text-sidebar-foreground/70" onClick={() => setImportOpen(true)}>
            <UploadIcon />
            <span>Import app</span>
          </SidebarMenuButton>
        </SidebarMenuItem>
      </SidebarMenu>
      <ImportDialog
        open={importOpen}
        onOpenChange={setImportOpen}
        onImported={(app, withSource) => {
          const services = withSource.filter((id): id is SqlService => id === "postgres" || id === "mysql");
          if (services.length > 0) setOffer({ app, services });
        }}
      />
      <AlertDialog open={!!offer} onOpenChange={(next) => !next && setOffer(undefined)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Sync from the source now or later?</AlertDialogTitle>
            <AlertDialogDescription>
              {offer?.app} has source settings for its {offer && offer.services.length > 1 ? "databases" : "database"}. Syncing copies the source's data into
              the local database.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Later</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                setSyncQueue(offer);
                setOffer(undefined);
              }}
            >
              Now
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      {syncQueue && syncQueue.services[0] && (
        <SyncDialog
          key={`${syncQueue.app}/${syncQueue.services[0]}`}
          app={syncQueue.app}
          service={syncQueue.services[0]}
          open
          onOpenChange={() => setSyncQueue((q) => (q && q.services.length > 1 ? { ...q, services: q.services.slice(1) } : undefined))}
        />
      )}
    </SidebarGroup>
  );
}
