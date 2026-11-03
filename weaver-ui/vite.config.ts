import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';

/**
 * Vite 与 Vitest 共用此配置。
 *
 * - `@/` 始终解析到 `src/`，目录调整后导入无需跟随修改。
 * - 环境变量统一放在仓库根目录的 `.env`（`envDir` 指向上一级），
 *   服务端与前端共用同一份配置，不再维护两个文件。
 * - 开发期间将 `/api` 代理到 Spring Boot 服务端，使浏览器保持同源，
 *   服务端无需额外配置 CORS。
 */

/** 仓库根目录；`.env` 与 `.env.example` 都在这里。 */
const repoRoot = fileURLToPath(new URL('..', import.meta.url));

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, repoRoot, '');
  // 命令行环境变量优先，便于临时指向另一个后端；否则读根目录 .env。
  const apiTarget =
    process.env.VITE_API_TARGET || env.VITE_API_TARGET || 'http://localhost:8080';

  return {
    envDir: repoRoot,
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
          target: apiTarget,
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
  };
});
