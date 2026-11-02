import { useState, type ComponentProps } from "react";
import { RocketIcon } from "lucide-react";
import { NavApps } from "@/components/nav-apps";
import { NavFooter } from "@/components/nav-footer";
import { SidebarSearch } from "@/components/sidebar-search";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
} from "@/components/ui/sidebar";
import { href, type Route } from "@/lib/router";

export function AppSidebar({ route, ...props }: ComponentProps<typeof Sidebar> & { route: Route }) {
  const [query, setQuery] = useState("");
  return (
    <Sidebar variant="inset" {...props}>
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton size="lg" render={<a href={href.home()} />}>
              <div className="flex aspect-square size-8 items-center justify-center rounded-lg bg-sidebar-primary text-sidebar-primary-foreground">
                <RocketIcon className="size-4" />
              </div>
              <div className="grid flex-1 text-left text-sm leading-tight">
                <span className="truncate font-heading font-semibold">Gantry</span>
                <span className="truncate text-xs text-sidebar-foreground/70">Local infrastructure</span>
              </div>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
        <SidebarSearch value={query} onChange={setQuery} />
      </SidebarHeader>
      <SidebarContent className="overflow-hidden">
        <ScrollArea className="flex-1">
          <NavApps route={route} query={query} />
        </ScrollArea>
      </SidebarContent>
      <SidebarFooter>
        <NavFooter route={route} />
      </SidebarFooter>
    </Sidebar>
  );
}
