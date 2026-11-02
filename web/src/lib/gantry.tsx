import { createContext, useContext, type ReactNode } from "react";
import { api } from "@/api";
import { useLoad } from "@/hooks";
import type { AppSummary, Catalog } from "@/types";

const APPS_POLL_MS = 5000;

interface GantryData {
  apps: AppSummary[] | undefined;
  appsError: string | undefined;
  catalog: Catalog | undefined;
  /** Refetch the app list, e.g. after creating, starting or destroying an app. */
  reloadApps: () => void;
}

const GantryContext = createContext<GantryData | undefined>(undefined);

/** App list and catalog shared by the sidebar and pages, so a change on one page shows up everywhere. */
export function GantryProvider({ children }: { children: ReactNode }) {
  const apps = useLoad(api.apps, [], APPS_POLL_MS);
  const catalog = useLoad(api.catalog, []);
  return (
    <GantryContext.Provider value={{ apps: apps.data, appsError: apps.error, catalog: catalog.data, reloadApps: apps.reload }}>
      {children}
    </GantryContext.Provider>
  );
}

export function useGantry(): GantryData {
  const value = useContext(GantryContext);
  if (!value) throw new Error("useGantry must be used inside GantryProvider");
  return value;
}
