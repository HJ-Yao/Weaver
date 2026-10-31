import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

/**
 * Vite 与 Vitest 共用此配置。
 *
 * - `@/` 始终解析到 `src/`，目录调整后导入无需跟随修改。
 * - 开发期间将 `/api` 代理到 Spring Boot 服务端，使浏览器保持同源，
 *   服务端无需额外配置 CORS。
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
    // 必须解析 CSS Modules，组件测试才能断言渲染出的布局标识。
    css: true,
    restoreMocks: true,
  },
});
