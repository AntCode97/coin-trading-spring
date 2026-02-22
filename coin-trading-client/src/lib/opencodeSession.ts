type OpencodeMessagePart = {
  type?: string;
  text?: string;
};

type OpencodeMessage = {
  info?: {
    id?: string;
    role?: string;
    time?: { created?: number };
  };
  parts?: OpencodeMessagePart[];
};

export type AgentAction = {
  type: 'ADD' | 'PARTIAL_TP' | 'FULL_EXIT' | 'HOLD' | 'WAIT_RETEST' | string;
  title: string;
  reason: string;
  targetPrice?: number | null;
  sizePercent?: number | null;
  urgency: 'LOW' | 'MEDIUM' | 'HIGH' | string;
};

export type AgentAdvice = {
  analysis: string;
  confidence: number;
  actions: AgentAction[];
};

export type ProviderStatus = {
  connected: string[];
  all: Array<{ id: string; connected: boolean }>;
};

const SESSION_KEY = 'guided-agent.opencode.sessionId.v1';
const BASE_URL_KEY = 'guided-agent.opencode.baseUrl.v1';
const BASIC_AUTH_KEY = 'guided-agent.opencode.basicAuth.v1';
const DIRECTORY_KEY = 'guided-agent.opencode.directory.v1';

export type OpencodeConnectionConfig = {
  baseUrl: string;
  basicAuth?: string;
  directory?: string;
};

function getBaseUrl(): string {
  return (
    window.localStorage.getItem(BASE_URL_KEY)?.trim() ||
    (import.meta.env.VITE_OPENCODE_BASE_URL as string | undefined)?.trim() ||
    'http://127.0.0.1:4096'
  );
}

function getDirectory(): string | undefined {
  return window.localStorage.getItem(DIRECTORY_KEY)?.trim() ||
    (import.meta.env.VITE_OPENCODE_DIRECTORY as string | undefined)?.trim() ||
    undefined;
}

function getBasicAuth(): string | undefined {
  return window.localStorage.getItem(BASIC_AUTH_KEY)?.trim() ||
    (import.meta.env.VITE_OPENCODE_BASIC_AUTH as string | undefined)?.trim() ||
    undefined;
}

export function getConnectionConfig(): OpencodeConnectionConfig {
  return {
    baseUrl: getBaseUrl(),
    basicAuth: getBasicAuth(),
    directory: getDirectory(),
  };
}

export function saveConnectionConfig(config: OpencodeConnectionConfig): void {
  window.localStorage.setItem(BASE_URL_KEY, config.baseUrl.trim());
  if (config.basicAuth && config.basicAuth.trim().length > 0) {
    window.localStorage.setItem(BASIC_AUTH_KEY, config.basicAuth.trim());
  } else {
    window.localStorage.removeItem(BASIC_AUTH_KEY);
  }
  if (config.directory && config.directory.trim().length > 0) {
    window.localStorage.setItem(DIRECTORY_KEY, config.directory.trim());
  } else {
    window.localStorage.removeItem(DIRECTORY_KEY);
  }
}

export function clearSessionId(): void {
  window.localStorage.removeItem(SESSION_KEY);
}

export async function getProviderStatus(): Promise<ProviderStatus> {
  const candidates = ['/provider', '/provider/list'];
  let lastError: Error | null = null;

  for (const path of candidates) {
    try {
      const payload = await requestJson<unknown>(path, { method: 'GET' });
      if (payload && typeof payload === 'object') {
        const root = payload as Record<string, unknown>;
        const data = (root.data as Record<string, unknown> | undefined) ?? root;
        const connectedRaw = data.connected;
        const allRaw = data.all;
        const connected = Array.isArray(connectedRaw)
          ? connectedRaw.filter((x): x is string => typeof x === 'string')
          : [];
        const all = Array.isArray(allRaw)
          ? allRaw
            .map((x) => {
              if (!x || typeof x !== 'object') return null;
              const row = x as Record<string, unknown>;
              const id = typeof row.id === 'string' ? row.id : null;
              if (!id) return null;
              return {
                id,
                connected: connected.includes(id),
              };
            })
            .filter((x): x is { id: string; connected: boolean } => x !== null)
          : connected.map((id) => ({ id, connected: true }));
        return { connected, all };
      }
    } catch (e) {
      lastError = e instanceof Error ? e : new Error(String(e));
    }
  }

  if (lastError) throw lastError;
  return { connected: [], all: [] };
}

export async function startProviderLogin(provider: string): Promise<{ authUrl?: string }> {
  const providerKey = provider.trim().toLowerCase();
  const attempts: Array<{ path: string; body?: Record<string, unknown> }> = [
    { path: '/auth/login', body: { provider: providerKey } },
    { path: '/provider/login', body: { provider: providerKey } },
    { path: `/provider/${encodeURIComponent(providerKey)}/login` },
  ];

  let lastError: Error | null = null;
  for (const attempt of attempts) {
    try {
      const payload = await requestJson<unknown>(attempt.path, {
        method: 'POST',
        body: attempt.body ? JSON.stringify(attempt.body) : undefined,
      });
      if (payload && typeof payload === 'object') {
        const root = payload as Record<string, unknown>;
        const data = (root.data as Record<string, unknown> | undefined) ?? root;
        const authUrl = [data.authUrl, data.url, data.verificationUri]
          .find((x): x is string => typeof x === 'string' && x.length > 0);
        return { authUrl };
      }
      return {};
    } catch (e) {
      lastError = e instanceof Error ? e : new Error(String(e));
    }
  }

  if (lastError) throw lastError;
  throw new Error('Provider 로그인 시작에 실패했습니다.');
}

function buildHeaders(): Record<string, string> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  const basicAuth = getBasicAuth();
  if (basicAuth) {
    headers.Authorization = `Basic ${basicAuth}`;
  }
  return headers;
}

async function requestJson<T>(path: string, init: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${getBaseUrl()}${path}`, {
      ...init,
      headers: {
        ...buildHeaders(),
        ...(init.headers ?? {}),
      },
    });
  } catch (error) {
    const reason = error instanceof Error ? error.message : String(error);
    throw new Error(
      `OpenCode 서버(${getBaseUrl()})에 연결할 수 없습니다. ` +
      `먼저 \`opencode serve --hostname 127.0.0.1 --port 4096\` 로 서버를 실행하세요. (${reason})`
    );
  }
  if (!response.ok) {
    const body = await response.text().catch(() => '');
    throw new Error(`OpenCode API 오류 (${response.status}): ${body || response.statusText}`);
  }
  return (await response.json()) as T;
}

function extractSessionId(payload: unknown): string | null {
  if (!payload || typeof payload !== 'object') return null;
  const root = payload as Record<string, unknown>;
  const data = (root.data as Record<string, unknown> | undefined) ?? (root['200'] as Record<string, unknown> | undefined);
  const candidates = [root.id, data?.id];
  for (const candidate of candidates) {
    if (typeof candidate === 'string' && candidate.length > 0) return candidate;
  }
  return null;
}

export async function ensureSessionId(): Promise<string> {
  const existing = window.localStorage.getItem(SESSION_KEY);
  if (existing) return existing;

  const payload = await requestJson<unknown>('/session', {
    method: 'POST',
    body: JSON.stringify({
      title: 'guided-trading-dashboard-agent',
      permission: [{ permission: 'question', action: 'deny', pattern: '*' }],
    }),
  });

  const id = extractSessionId(payload);
  if (!id) {
    throw new Error('OpenCode 세션 생성 응답에서 session id를 찾을 수 없습니다.');
  }
  window.localStorage.setItem(SESSION_KEY, id);
  return id;
}

export async function submitAgentPrompt(options: {
  sessionId: string;
  textPrompt: string;
  imageDataUrl?: string;
}): Promise<void> {
  const parts: Array<Record<string, unknown>> = [{ type: 'text', text: options.textPrompt }];
  if (options.imageDataUrl) {
    parts.push({
      type: 'file',
      mime: 'image/png',
      url: options.imageDataUrl,
      filename: 'guided-chart.png',
    });
  }

  const directory = getDirectory();
  const promptPath = directory
    ? `/session/${encodeURIComponent(options.sessionId)}/prompt_async?directory=${encodeURIComponent(directory)}`
    : `/session/${encodeURIComponent(options.sessionId)}/prompt_async`;

  await requestJson<unknown>(promptPath, {
    method: 'POST',
    body: JSON.stringify({
      agent: 'hephaestus',
      tools: {
        question: false,
      },
      parts,
    }),
  });
}

export async function getSessionMessages(sessionId: string): Promise<OpencodeMessage[]> {
  const paths = [
    `/session/${encodeURIComponent(sessionId)}/messages`,
    `/session/${encodeURIComponent(sessionId)}/message`,
  ];

  let lastError: Error | null = null;
  for (const path of paths) {
    try {
      const payload = await requestJson<unknown>(path, { method: 'GET' });
      if (payload && typeof payload === 'object') {
        const root = payload as Record<string, unknown>;
        const data = (root.data as unknown[]) ?? (root['200'] as unknown[]);
        if (Array.isArray(data)) {
          return data as OpencodeMessage[];
        }
      }
    } catch (e) {
      lastError = e instanceof Error ? e : new Error(String(e));
    }
  }

  if (lastError) {
    throw lastError;
  }

  return [];
}

function latestAssistantText(messages: OpencodeMessage[]): { text: string | null; created: number } {
  const assistants = messages
    .filter((m) => m.info?.role === 'assistant')
    .sort((a, b) => (a.info?.time?.created ?? 0) - (b.info?.time?.created ?? 0));
  const latest = assistants.at(-1);
  if (!latest) return { text: null, created: 0 };
  const text = latest.parts
    ?.filter((p) => p.type === 'text' && typeof p.text === 'string')
    .map((p) => p.text as string)
    .join('\n')
    .trim();
  return { text: text && text.length > 0 ? text : null, created: latest.info?.time?.created ?? 0 };
}

function extractJsonBlock(raw: string): string {
  const start = raw.indexOf('{');
  const end = raw.lastIndexOf('}');
  if (start >= 0 && end > start) {
    return raw.substring(start, end + 1);
  }
  return raw;
}

export function parseAdvice(raw: string): AgentAdvice {
  try {
    const parsed = JSON.parse(extractJsonBlock(raw)) as Partial<AgentAdvice>;
    return {
      analysis: typeof parsed.analysis === 'string' ? parsed.analysis : raw,
      confidence: typeof parsed.confidence === 'number' ? Math.max(0, Math.min(100, parsed.confidence)) : 50,
      actions: Array.isArray(parsed.actions) ? parsed.actions.map((a) => ({
        type: typeof a?.type === 'string' ? a.type : 'HOLD',
        title: typeof a?.title === 'string' ? a.title : '관망',
        reason: typeof a?.reason === 'string' ? a.reason : '시장 노이즈 구간',
        targetPrice: typeof a?.targetPrice === 'number' ? a.targetPrice : null,
        sizePercent: typeof a?.sizePercent === 'number' ? a.sizePercent : null,
        urgency: typeof a?.urgency === 'string' ? a.urgency : 'MEDIUM',
      })) : [],
    };
  } catch {
    return {
      analysis: raw,
      confidence: 50,
      actions: [],
    };
  }
}

export async function waitForAssistantAdvice(options: {
  sessionId: string;
  sinceEpochMs: number;
  timeoutMs?: number;
  intervalMs?: number;
}): Promise<AgentAdvice> {
  try {
    return await waitForAssistantAdviceViaStream(options);
  } catch {
    return await waitForAssistantAdviceViaPolling(options);
  }
}

async function waitForAssistantAdviceViaPolling(options: {
  sessionId: string;
  sinceEpochMs: number;
  timeoutMs?: number;
  intervalMs?: number;
}): Promise<AgentAdvice> {
  const timeoutMs = options.timeoutMs ?? 45000;
  const intervalMs = options.intervalMs ?? 1500;
  const started = Date.now();

  while (Date.now() - started < timeoutMs) {
    const messages = await getSessionMessages(options.sessionId);
    const latest = latestAssistantText(messages);
    if (latest.text && latest.created >= options.sinceEpochMs) {
      return parseAdvice(latest.text);
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }

  throw new Error('OpenCode 응답 대기 시간이 초과되었습니다.');
}

async function waitForAssistantAdviceViaStream(options: {
  sessionId: string;
  sinceEpochMs: number;
  timeoutMs?: number;
}): Promise<AgentAdvice> {
  const timeoutMs = options.timeoutMs ?? 45000;
  const directory = getDirectory();
  const eventPath = directory
    ? `/event?directory=${encodeURIComponent(directory)}`
    : '/event';

  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${getBaseUrl()}${eventPath}`, {
      method: 'GET',
      headers: buildHeaders(),
      signal: controller.signal,
    });

    if (!response.ok || !response.body) {
      throw new Error(`SSE 연결 실패 (${response.status})`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const chunks = buffer.split('\n\n');
      buffer = chunks.pop() ?? '';
      for (const chunk of chunks) {
        if (!isSessionRelatedEvent(chunk, options.sessionId)) continue;
        const messages = await getSessionMessages(options.sessionId);
        const latest = latestAssistantText(messages);
        if (latest.text && latest.created >= options.sinceEpochMs - 1000) {
          return parseAdvice(latest.text);
        }
      }
    }
  } finally {
    window.clearTimeout(timer);
    controller.abort();
  }

  throw new Error('OpenCode SSE 응답 대기 시간이 초과되었습니다.');
}

function isSessionRelatedEvent(chunk: string, sessionId: string): boolean {
  const lines = chunk.split('\n').map((line) => line.trim());
  const dataLines = lines
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).trim())
    .filter((line) => line.length > 0);

  for (const line of dataLines) {
    if (line.includes(sessionId)) {
      return true;
    }

    try {
      const parsed = JSON.parse(line);
      const serialized = JSON.stringify(parsed);
      if (serialized.includes(sessionId)) {
        return true;
      }
    } catch {
      continue;
    }
  }

  return false;
}
