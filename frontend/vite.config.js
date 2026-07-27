import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In production the SPA is served by Nginx from the same origin, so /api is just a
// path. In dev, Vite proxies it to the app so there is no CORS in the loop.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.SNIP_API || 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});
