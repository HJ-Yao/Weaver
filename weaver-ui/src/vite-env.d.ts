/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** API base path; defaults to `/api` (proxied by Vite in development). */
  readonly VITE_API_BASE?: string;
  /** Set to `"false"` to stream from the real server instead of the mock store. */
  readonly VITE_USE_MOCK?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
