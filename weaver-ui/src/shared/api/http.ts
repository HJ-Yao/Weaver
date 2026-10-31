/**
 * HTTP 边界。
 *
 * 应用中的所有网络调用都经过此模块，使基础 URL、请求头和错误结构集中在一处。
 * 组件不得直接调用 `fetch`。
 */

/** 基础路径；开发期间 Vite 将 `/api` 代理到 Spring Boot。 */
export const API_BASE = import.meta.env.VITE_API_BASE ?? '/api';

/** 携带 HTTP 状态的错误，调用方据此区分 404 与 5xx。 */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

/**
 * 执行 JSON 请求并反序列化响应。
 *
 * `204 No Content` 会解析为 `undefined`，因此返回类型通过断言指定，而不进行解析。
 */
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...init.headers,
    },
  });

  if (!response.ok) {
    throw new ApiError(response.status, await readError(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

async function readError(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string };
    return body.message ?? response.statusText;
  } catch {
    return response.statusText;
  }
}
