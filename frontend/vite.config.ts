import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The test SPA calls only the public CCProxy API (same origin as it will be served from, under
// /ccproxy). In dev we proxy /ccproxy to the local backend so there is no separate dev API.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/ccproxy": {
        target: "http://localhost:8080",
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/ccproxy/, ""),
      },
    },
  },
});
