/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API 基础路径；默认为 `/api`，开发期间由 Vite 代理。 */
  readonly VITE_API_BASE?: string;
  /** 设为 `"false"` 时从真实服务端流式读取，不使用模拟 store。 */
  readonly VITE_USE_MOCK?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
