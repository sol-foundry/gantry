import { useEffect, useState } from "react";

/** Explorer pages, keyed by the route segment the catalog's `ui` builtin names. */
export type Tool = "postgres" | "mysql" | "queue" | "gateway";

export type Route =
  | { page: "home" }
  | { page: "new-app" }
  | { page: "app"; app: string }
  | { page: "tool"; app: string; tool: Tool }
  | { page: "services" }
  | { page: "service"; service: string };

const TOOLS: Tool[] = ["postgres", "mysql", "queue", "gateway"];

/** Hash routes keep the dashboard a single static page the Kotlin server can serve for any path. */
export function parseRoute(hash: string): Route {
  const [section, name, tool] = hash.replace(/^#\/?/, "").split("/").filter(Boolean).map(decodeURIComponent);
  if (section === "new") return { page: "new-app" };
  if (section === "services") return name ? { page: "service", service: name } : { page: "services" };
  if (section === "apps" && name) {
    return tool && TOOLS.includes(tool as Tool) ? { page: "tool", app: name, tool: tool as Tool } : { page: "app", app: name };
  }
  return { page: "home" };
}

export const href = {
  home: () => "#/",
  newApp: () => "#/new",
  app: (app: string) => `#/apps/${encodeURIComponent(app)}`,
  tool: (app: string, tool: Tool) => `#/apps/${encodeURIComponent(app)}/${tool}`,
  services: () => "#/services",
  service: (id: string) => `#/services/${encodeURIComponent(id)}`,
};

export function navigate(to: string) {
  window.location.hash = to.replace(/^#/, "");
}

export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseRoute(window.location.hash));
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return route;
}
