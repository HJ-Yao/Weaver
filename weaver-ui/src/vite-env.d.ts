/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API 基础路径；默认为 `/api`，开发期间由 Vite 代理。 */
  readonly VITE_API_BASE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
