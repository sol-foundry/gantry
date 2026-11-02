import { lazy, StrictMode, Suspense } from "react";
import { createRoot } from "react-dom/client";
import { App } from "@/App";
import { Toaster } from "@/components/ui/sonner";
import { TooltipProvider } from "@/components/ui/tooltip";
import { GantryProvider } from "@/lib/gantry";
import { ThemeProvider } from "@/lib/theme";
import "./index.css";

// Visual feedback toolbar for coding agents, only under `npm run dev`; production builds drop it entirely.
const Agentation = import.meta.env.DEV ? lazy(() => import("agentation").then((m) => ({ default: m.Agentation }))) : null;

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ThemeProvider>
      <TooltipProvider>
        <GantryProvider>
          <App />
          <Toaster richColors position="bottom-right" />
          {Agentation && (
            <Suspense>
              <Agentation />
            </Suspense>
          )}
        </GantryProvider>
      </TooltipProvider>
    </ThemeProvider>
  </StrictMode>,
);
