// The one HTTP helper. The SPA is same-origin with the gateway, which proxies /ccproxy/* to the
// backend, so every call is a relative /ccproxy path with a Bearer token (a super-admin JWT or a
// tenant secret — the backend accepts either on the same header).

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

function parse(text: string): any {
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

export async function request(
  method: string,
  path: string,
  opts: { token?: string; body?: unknown } = {},
): Promise<any> {
  const headers: Record<string, string> = {};
  if (opts.token) headers.Authorization = `Bearer ${opts.token}`;
  if (opts.body !== undefined) headers["Content-Type"] = "application/json";
  const res = await fetch(`/ccproxy${path}`, {
    method,
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  });
  const data = parse(await res.text());
  if (!res.ok) {
    const msg = data?.message || data?.error?.message || data || res.statusText;
    throw new ApiError(res.status, typeof msg === "string" ? msg : JSON.stringify(msg));
  }
  return data;
}

/** Log in as the platform operator; returns the super-admin JWT. */
export async function superadminLogin(password: string): Promise<string> {
  const r = await request("POST", "/superadmin/login", { body: { password } });
  return r.token;
}
