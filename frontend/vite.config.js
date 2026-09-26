import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";

// In dev, the frontend talks to the Spring Boot auth-service via a proxy so
// that requests to /api are forwarded to http://localhost:8081 (no CORS issues).
// Override the backend target with VITE_PROXY_TARGET if needed.
// Default: the API gateway (which fronts auth-service + subscription-service).
const target = process.env.VITE_PROXY_TARGET || "http://localhost:8080";

// The repository root. /docs lives there and is imported as raw markdown by
// src/data/docs.js, so the dev server has to be allowed to read outside this
// package. The Docker build copies docs/ to the same relative position.
const repoRoot = fileURLToPath(new URL("..", import.meta.url));

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    fs: {
      allow: [fileURLToPath(new URL(".", import.meta.url)), repoRoot],
    },
    proxy: {
      "/api": {
        target,
        changeOrigin: true,
      },
    },
  },
});
