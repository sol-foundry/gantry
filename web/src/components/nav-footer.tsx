import { LayersIcon, MonitorIcon, MoonIcon, SunIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { SidebarMenu, SidebarMenuItem } from "@/components/ui/sidebar";
import { href, type Route } from "@/lib/router";
import { useTheme, type Theme } from "@/lib/theme";
import { cn } from "@/lib/utils";

const THEMES: { value: Theme; label: string; icon: typeof SunIcon }[] = [
  { value: "light", label: "Light", icon: SunIcon },
  { value: "dark", label: "Dark", icon: MoonIcon },
  { value: "system", label: "System", icon: MonitorIcon },
];

/** Solid fills rather than outlines, sized like the navigation rows above. */
const FOOTER_BUTTON = "bg-sidebar-accent text-sidebar-accent-foreground hover:bg-foreground/12 aria-expanded:bg-foreground/12";

/** Bottom of the sidebar: the services catalog and the theme picker, side by side. */
export function NavFooter({ route }: { route: Route }) {
  const { theme, setTheme } = useTheme();
  const ThemeIcon = THEMES.find((t) => t.value === theme)?.icon ?? MonitorIcon;
  const onServices = route.page === "services" || route.page === "service";

  return (
    <>
      <SidebarMenu>
        <SidebarMenuItem className="flex items-center gap-1">
          <Button
            variant="ghost"
            size="sm"
            nativeButton={false}
            className={cn(FOOTER_BUTTON, "h-8 flex-1 justify-start gap-2 px-2 text-[13px] [&_svg]:size-4", onServices && "bg-foreground/12 font-medium")}
            render={<a href={href.services()} />}
          >
            <LayersIcon />
            Services
          </Button>
          <DropdownMenu>
            <DropdownMenuTrigger render={<Button variant="ghost" size="icon-sm" className={cn(FOOTER_BUTTON, "size-8 [&_svg]:size-4")} title={`Theme: ${theme}`} />}>
              <ThemeIcon />
              <span className="sr-only">Theme</span>
            </DropdownMenuTrigger>
            <DropdownMenuContent side="top" align="end" className="w-40">
              <DropdownMenuGroup>
                <DropdownMenuLabel>Theme</DropdownMenuLabel>
                <DropdownMenuRadioGroup value={theme} onValueChange={(value) => setTheme(value as Theme)}>
                  {THEMES.map(({ value, label, icon: Icon }) => (
                    <DropdownMenuRadioItem key={value} value={value}>
                      <Icon />
                      {label}
                    </DropdownMenuRadioItem>
                  ))}
                </DropdownMenuRadioGroup>
              </DropdownMenuGroup>
            </DropdownMenuContent>
          </DropdownMenu>
        </SidebarMenuItem>
      </SidebarMenu>
    </>
  );
}
