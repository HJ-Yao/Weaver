/**
 * HTTP boundary.
 *
 * Every network call in the app goes through this module so that base URL,
 * headers and error shape stay in one place. Components must never call
 * `fetch` directly.
 */

/** Base path; Vite proxies `/api` to Spring Boot in development. */
export const API_BASE = import.meta.env.VITE_API_BASE ?? '/api';

/** Error carrying the HTTP status so callers can branch on 404 vs 5xx. */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

/**
 * Perform a JSON request and deserialize the response.
 *
 * `204 No Content` resolves to `undefined`, which is why the return type is
 * asserted rather than parsed.
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
