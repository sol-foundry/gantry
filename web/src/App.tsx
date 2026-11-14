import { Fragment, useEffect, useState } from "react";
import { AppSidebar } from "@/components/app-sidebar";
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Separator } from "@/components/ui/separator";
import { SidebarInset, SidebarProvider, SidebarTrigger } from "@/components/ui/sidebar";
import { useGantry } from "@/lib/gantry";
import { HeaderContext } from "@/lib/header";
import { href, navigate, useRoute, type Route } from "@/lib/router";
import { ServiceIcon, TOOL_LABELS, serviceLabel } from "@/lib/services";
import { AppPage } from "@/pages/AppPage";
import { DatabasePage } from "@/pages/DatabasePage";
import { NewAppPage } from "@/pages/NewAppPage";
import { GatewayPage } from "@/pages/GatewayPage";
import { QueuePage } from "@/pages/QueuePage";
import { ServicePage } from "@/pages/ServicePage";
import { ServicesPage } from "@/pages/ServicesPage";

type Crumb = [label: string, link: string | null, icon?: string];

/**
 * Breadcrumb trail: a null link is a plain label, the last entry is the current page. Tool pages may replace their
 * crumb with richer content (the database name) through the header's crumb slot.
 */
function trail(route: Route): Crumb[] {
  switch (route.page) {
    case "home":
      return [["Apps", null]];
    case "new-app":
      return [
        ["Apps", null],
        ["New app", href.newApp()],
      ];
    case "app":
      return [
        ["Apps", null],
        [route.app, href.app(route.app)],
      ];
    case "tool":
      return [
        ["Apps", null],
        [route.app, href.app(route.app)],
        [TOOL_LABELS[route.tool], href.tool(route.app, route.tool), route.tool === "queue" ? "elasticmq" : route.tool],
      ];
    case "services":
      return [["Services", href.services()]];
    case "service":
      return [
        ["Services", href.services()],
        [serviceLabel(route.service), href.service(route.service), route.service],
      ];
  }
}

/** There is no apps index: land on the first app, or on New app when there are none yet. */
function HomeRedirect() {
  const { apps } = useGantry();
  useEffect(() => {
    if (apps) navigate(apps.length > 0 ? href.app(apps[0]!.name) : href.newApp());
  }, [apps]);
  return null;
}

function Page({ route }: { route: Route }) {
  switch (route.page) {
    case "home":
      return <HomeRedirect />;
    case "new-app":
      return <NewAppPage />;
    case "app":
      return <AppPage key={route.app} app={route.app} />;
    case "tool":
      if (route.tool === "queue") return <QueuePage key={route.app} app={route.app} />;
      if (route.tool === "gateway") return <GatewayPage key={route.app} app={route.app} />;
      return <DatabasePage key={`${route.app}/${route.tool}`} app={route.app} service={route.tool} />;
    case "services":
      return <ServicesPage />;
    case "service":
      return <ServicePage key={route.service} service={route.service} />;
  }
}

export function App() {
  const route = useRoute();
  const crumbs = trail(route);
  const [crumbSlot, setCrumbSlot] = useState<HTMLElement | null>(null);
  const [actionsSlot, setActionsSlot] = useState<HTMLElement | null>(null);
  const [crumbFilled, setCrumbFilled] = useState(false);

  // A page that fills the crumb slot replaces the plain label of the current page.
  useEffect(() => {
    if (!crumbSlot) return;
    const sync = () => setCrumbFilled(crumbSlot.childNodes.length > 0);
    sync();
    const observer = new MutationObserver(sync);
    observer.observe(crumbSlot, { childList: true });
    return () => observer.disconnect();
  }, [crumbSlot]);

  return (
    // Pinned to the viewport: the main content panel scrolls, never the window, so the sidebar and breadcrumb stay put.
    <HeaderContext.Provider value={{ crumb: crumbSlot, actions: actionsSlot }}>
      <SidebarProvider className="h-svh overflow-hidden">
        <AppSidebar route={route} />
        <SidebarInset className="min-h-0 overflow-hidden">
          <header className="flex h-11 shrink-0 items-center gap-2 border-b px-4">
            <SidebarTrigger className="-ml-1" />
            <Separator orientation="vertical" className="mr-2 data-vertical:h-4 data-vertical:self-center" />
            <Breadcrumb className="min-w-0 flex-1">
              <BreadcrumbList className="flex-nowrap">
                {crumbs.map(([label, link, icon], i) => {
                  const last = i === crumbs.length - 1;
                  return (
                    <Fragment key={`${i}-${label}`}>
                      {i > 0 && <BreadcrumbSeparator />}
                      <BreadcrumbItem className={last ? "min-w-0" : undefined}>
                        {last ? (
                          <BreadcrumbPage className="flex min-w-0 items-center gap-1.5">
                            <span ref={setCrumbSlot} className="contents" />
                            {!crumbFilled && (
                              <>
                                {icon && <ServiceIcon id={icon} className="size-4 shrink-0" />}
                                <span className="truncate">{label}</span>
                              </>
                            )}
                          </BreadcrumbPage>
                        ) : link ? (
                          <BreadcrumbLink href={link}>{label}</BreadcrumbLink>
                        ) : (
                          <span>{label}</span>
                        )}
                      </BreadcrumbItem>
                    </Fragment>
                  );
                })}
              </BreadcrumbList>
            </Breadcrumb>
            <div ref={setActionsSlot} className="flex shrink-0 items-center gap-2" />
          </header>
          {/* Overlay scrollbar: no gutter, hidden until the panel scrolls. */}
          <ScrollArea className="min-h-0 min-w-0 flex-1">
            <div className="flex min-w-0 flex-col gap-6 p-4 md:p-6">
              <Page route={route} />
            </div>
          </ScrollArea>
        </SidebarInset>
      </SidebarProvider>
    </HeaderContext.Provider>
  );
}
