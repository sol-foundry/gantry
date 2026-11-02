import { resolve } from "node:path";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// `npm run dev` proxies API calls to a running `gantry dashboard`.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { "@": resolve(import.meta.dirname, "./src") } },
  // The dashboard rejects requests whose Origin isn't its own host, so the dev proxy presents itself as it.
  server: { proxy: { "/api": { target: "http://localhost:7070", changeOrigin: true, headers: { origin: "http://localhost:7070" } } } },
  // Served from localhost by the dashboard, so one bundle beats code-splitting; only warn past 1 MB.
  build: { outDir: "dist", emptyOutDir: true, chunkSizeWarningLimit: 1024 },
});
