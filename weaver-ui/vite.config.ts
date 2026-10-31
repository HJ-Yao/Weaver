import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

/**
 * Vite + Vitest share this config.
 *
 * - `@/` always resolves to `src/` so imports survive directory moves.
 * - `/api` is proxied to the Spring Boot server during development, which keeps
 *   the browser on a single origin (no CORS setup on the server).
 */
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // CSS Modules must be resolved so component tests can assert on rendered layout hooks.
    css: true,
    restoreMocks: true,
  },
});
