import { type GuidedAgentContextResponse } from '../api';

// ---------- Types ----------

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

export type LlmConnectionStatus = 'connected' | 'disconnected' | 'expired' | 'checking' | 'error';
export type LlmProviderId = 'openai' | 'zai';
export type ZaiEndpointMode = 'coding' | 'general';
export type DelegationMode = 'AUTO_AND_MANUAL' | 'AUTO_ONLY' | 'MANUAL_ONLY';

export type ChatMessage = {
  id: string;
  role: 'user' | 'assistant' | 'system' | 'tool';
  content: string;
  timestamp: number;
  toolCall?: { name: string; args: string; result?: string };
  actions?: AgentAction[];
};

export interface OneShotUsageMeta {
  estimatedInputTokens: number;
  estimatedOutputTokens: number;
  totalTokens: number;
}

export interface ZaiConcurrencyStatus {
  active: number;
  queued: number;
  max: number;
}

interface SendChatMessageOptions {
  userMessage: string;
  model?: string;
  provider?: LlmProviderId;
  context?: GuidedAgentContextResponse | null;
  mcpTools?: McpTool[];
  tradingMode?: TradingMode;
  zaiEndpointMode?: ZaiEndpointMode;
  delegationMode?: DelegationMode;
  zaiDelegateModel?: string;
  onStreamDelta?: (accumulated: string) => void;
  onToolCall?: (toolName: string, args: string) => void;
  onToolResult?: (toolName: string, result: string) => void;
}

interface OneShotOptions {
  prompt: string;
  model?: string;
  provider?: LlmProviderId;
  context?: GuidedAgentContextResponse | null;
  tradingMode?: TradingMode;
  mcpTools?: McpTool[];
  zaiEndpointMode?: ZaiEndpointMode;
}

interface ZaiToolLoopResult {
  text: string;
  toolMessages: ChatMessage[];
  usage: {
    promptTokens: number;
    completionTokens: number;
    totalTokens: number;
  };
}

// ---------- Provider Constants ----------

const CODEX_API_URL = 'https://chatgpt.com/backend-api/codex/responses';
const ZAI_DEFAULT_MODEL = 'glm-4.7-flash';
const ZAI_QUEUE_TIMEOUT_MS = 30_000;
const ZAI_MAX_CONCURRENCY = 3;
const ZAI_DELEGATE_TOOL_NAME = 'delegate_to_zai_agent';

export const CODEX_MODELS = [
  { id: 'gpt-5.3-codex', label: 'GPT-5.3 Codex' },
  { id: 'o3', label: 'o3' },
  { id: 'o4-mini', label: 'o4-mini' },
  { id: 'gpt-4.1', label: 'GPT-4.1' },
  { id: 'gpt-4.1-mini', label: 'GPT-4.1 mini' },
] as const;

export type CodexModelId = (typeof CODEX_MODELS)[number]['id'];

export const ZAI_MODELS = [
  { id: 'glm-5', label: 'GLM-5' },
  { id: 'glm-4.7', label: 'GLM-4.7' },
  { id: 'glm-4.7-flash', label: 'GLM-4.7-Flash' },
  { id: 'glm-4.7-flashx', label: 'GLM-4.7-FlashX' },
  { id: 'glm-4.6', label: 'GLM-4.6' },
  { id: 'glm-4.5', label: 'GLM-4.5' },
] as const;

export type ZaiModelId = (typeof ZAI_MODELS)[number]['id'];

// ---------- Provider Client State ----------

let cachedOpenAiToken: { accessToken: string; accountId?: string | null } | null = null;

type ConversationMessage =
  | { role: 'user'; content: string }
  | { role: 'assistant'; content: string };

const conversationHistoryByProvider: Record<LlmProviderId, ConversationMessage[]> = {
  openai: [],
  zai: [],
};

interface ZaiQueueWaiter {
  resolve: (release: () => void) => void;
  reject: (error: Error) => void;
  timeoutId: number;
}

const zaiQueue: ZaiQueueWaiter[] = [];
let zaiActiveCount = 0;
const zaiConcurrencyListeners = new Set<(status: ZaiConcurrencyStatus) => void>();

function emitZaiConcurrencyStatus(): void {
  const status = getZaiConcurrencyStatus();
  for (const listener of zaiConcurrencyListeners) {
    listener(status);
  }
}

function releaseZaiSlotOnceFactory(): () => void {
  let released = false;
  return () => {
    if (released) return;
    released = true;
    zaiActiveCount = Math.max(0, zaiActiveCount - 1);
    while (zaiActiveCount < ZAI_MAX_CONCURRENCY && zaiQueue.length > 0) {
      const waiter = zaiQueue.shift();
      if (!waiter) break;
      window.clearTimeout(waiter.timeoutId);
      zaiActiveCount += 1;
      waiter.resolve(releaseZaiSlotOnceFactory());
    }
    emitZaiConcurrencyStatus();
  };
}

async function acquireZaiSlot(timeoutMs = ZAI_QUEUE_TIMEOUT_MS): Promise<() => void> {
  if (zaiActiveCount < ZAI_MAX_CONCURRENCY) {
    zaiActiveCount += 1;
    emitZaiConcurrencyStatus();
    return releaseZaiSlotOnceFactory();
  }

  return new Promise((resolve, reject) => {
    const waiter: ZaiQueueWaiter = {
      resolve,
      reject,
      timeoutId: window.setTimeout(() => {
        const idx = zaiQueue.indexOf(waiter);
        if (idx >= 0) {
          zaiQueue.splice(idx, 1);
        }
        emitZaiConcurrencyStatus();
        reject(new Error('z.ai 동시 실행 한도(3)로 인해 대기 시간이 초과되었습니다.'));
      }, timeoutMs),
    };

    zaiQueue.push(waiter);
    emitZaiConcurrencyStatus();
  });
}

async function withZaiConcurrency<T>(run: () => Promise<T>): Promise<T> {
  const release = await acquireZaiSlot();
  try {
    return await run();
  } finally {
    release();
  }
}

export function getZaiConcurrencyStatus(): ZaiConcurrencyStatus {
  return {
    active: zaiActiveCount,
    queued: zaiQueue.length,
    max: ZAI_MAX_CONCURRENCY,
  };
}

export function subscribeZaiConcurrency(listener: (status: ZaiConcurrencyStatus) => void): () => void {
  zaiConcurrencyListeners.add(listener);
  listener(getZaiConcurrencyStatus());
  return () => {
    zaiConcurrencyListeners.delete(listener);
  };
}

function resolveProvider(provider?: LlmProviderId): LlmProviderId {
  return provider === 'zai' ? 'zai' : 'openai';
}

async function getOpenAiToken(): Promise<{ accessToken: string; accountId?: string | null }> {
  if (cachedOpenAiToken) return cachedOpenAiToken;
  const result = await window.desktopAuth?.getToken();
  if (!result?.accessToken) throw new Error('OpenAI 인증이 필요합니다. 로그인을 먼저 진행하세요.');
  cachedOpenAiToken = result;
  return cachedOpenAiToken;
}

export function clearClient(): void {
  cachedOpenAiToken = null;
}

export function clearConversation(provider?: LlmProviderId): void {
  if (provider) {
    conversationHistoryByProvider[provider] = [];
    return;
  }
  conversationHistoryByProvider.openai = [];
  conversationHistoryByProvider.zai = [];
}

// ---------- Connection ----------

export async function checkConnection(provider: LlmProviderId = 'openai'): Promise<LlmConnectionStatus> {
  const resolved = resolveProvider(provider);
  if (resolved === 'openai') {
    if (!window.desktopAuth) return 'disconnected';
    try {
      const result = await window.desktopAuth.checkStatus();
      return result.status;
    } catch {
      return 'error';
    }
  }

  if (!window.desktopZai) return 'disconnected';
  try {
    const result = await window.desktopZai.checkStatus();
    return result.status;
  } catch {
    return 'error';
  }
}

export async function startLogin(provider: LlmProviderId = 'openai'): Promise<void> {
  const resolved = resolveProvider(provider);
  if (resolved === 'openai') {
    if (!window.desktopAuth) throw new Error('데스크톱 앱에서만 사용 가능합니다.');
    await window.desktopAuth.startLogin();
    clearClient();
    return;
  }
  throw new Error('z.ai는 로그인 대신 API Key를 등록해서 사용합니다.');
}

export async function logout(provider: LlmProviderId = 'openai'): Promise<void> {
  const resolved = resolveProvider(provider);
  if (resolved === 'openai') {
    if (!window.desktopAuth) return;
    await window.desktopAuth.logout();
    clearClient();
    conversationHistoryByProvider.openai = [];
    return;
  }

  if (!window.desktopZai) return;
  await window.desktopZai.clearApiKey();
  conversationHistoryByProvider.zai = [];
}

export async function setZaiApiKey(apiKey: string): Promise<void> {
  if (!window.desktopZai) {
    throw new Error('데스크톱 앱에서만 사용 가능합니다.');
  }
  const result = await window.desktopZai.setApiKey(apiKey);
  if (!result.ok) {
    throw new Error(result.error || 'z.ai API Key 저장 실패');
  }
}

export async function clearZaiApiKey(): Promise<void> {
  if (!window.desktopZai) return;
  await window.desktopZai.clearApiKey();
  conversationHistoryByProvider.zai = [];
}

export function isManualDelegationAllowed(mode?: DelegationMode): boolean {
  return (mode ?? 'AUTO_AND_MANUAL') !== 'AUTO_ONLY';
}

function isAutoDelegationAllowed(mode?: DelegationMode): boolean {
  return (mode ?? 'AUTO_AND_MANUAL') !== 'MANUAL_ONLY';
}

// ---------- MCP 도구 ----------

function mcpToolsToOpenAiFunctions(tools: McpTool[]): object[] {
  return tools.map((tool) => ({
    type: 'function',
    name: tool.qualifiedName || tool.name,
    description: tool.description || tool.name,
    parameters: tool.inputSchema || { type: 'object', properties: {} },
  }));
}

function mcpToolsToZaiTools(tools: McpTool[]): DesktopZaiTool[] {
  return tools.map((tool) => ({
    type: 'function',
    function: {
      name: tool.qualifiedName || tool.name,
      description: tool.description || tool.name,
      parameters: tool.inputSchema || { type: 'object', properties: {} },
    },
  }));
}

function buildZaiDelegateToolSchema(): object {
  return {
    type: 'function',
    name: ZAI_DELEGATE_TOOL_NAME,
    description: 'z.ai 에이전트에게 하위 작업을 위임하고 결과를 받는다.',
    parameters: {
      type: 'object',
      properties: {
        task: {
          type: 'string',
          description: 'z.ai 에이전트에게 위임할 구체 작업 지시문',
        },
        model: {
          type: 'string',
          description: '선택적 z.ai 모델 ID (예: glm-4.7-flash)',
        },
      },
      required: ['task'],
    },
  };
}

export async function connectMcp(mcpUrl: string): Promise<McpTool[]> {
  return connectMcpServers([{ serverId: 'trading', url: mcpUrl }]);
}

export async function connectMcpServers(servers: DesktopMcpServerConfig[]): Promise<McpTool[]> {
  if (!window.desktopMcp) return [];
  const result = await window.desktopMcp.connectMany(servers);
  if (!result.ok) {
    console.warn('MCP 연결 실패:', result.error);
    return [];
  }
  return result.tools;
}

export async function listMcpTools(): Promise<McpTool[]> {
  if (!window.desktopMcp) return [];
  const result = await window.desktopMcp.listTools();
  return result.tools;
}

async function callMcpTool(name: string, args: Record<string, unknown>, tools?: McpTool[]): Promise<string> {
  const raw = await callMcpToolRaw(name, args, tools);
  if (raw.isError) {
    return JSON.stringify({ error: raw.content?.[0]?.text || '도구 실행 실패' });
  }
  const texts = (raw.content || [])
    .filter((c) => c.type === 'text' && c.text)
    .map((c) => c.text);
  return texts.join('\n') || JSON.stringify(raw);
}

function resolveToolRoute(
  name: string,
  tools?: McpTool[]
): { serverId?: string; toolName: string } {
  const matched = tools?.find((tool) => tool.qualifiedName === name || tool.name === name);
  if (matched) {
    return {
      serverId: matched.serverId,
      toolName: matched.originName || matched.name,
    };
  }
  return { toolName: name };
}

async function callMcpToolRaw(
  name: string,
  args: Record<string, unknown>,
  tools?: McpTool[]
): Promise<McpToolResult> {
  if (!window.desktopMcp) {
    return {
      content: [{ type: 'text', text: 'MCP 미연결' }],
      isError: true,
    };
  }
  const route = resolveToolRoute(name, tools);
  return window.desktopMcp.callTool(route.toolName, args, route.serverId || null);
}

export async function executeMcpTool(
  name: string,
  args: Record<string, unknown>,
  serverId?: string
): Promise<McpToolResult> {
  if (!window.desktopMcp) {
    return {
      content: [{ type: 'text', text: 'MCP 미연결' }],
      isError: true,
    };
  }
  return window.desktopMcp.callTool(name, args, serverId ?? null);
}

export async function getPlaywrightStatus(): Promise<PlaywrightMcpStatus | null> {
  if (!window.desktopMcp) return null;
  return window.desktopMcp.playwrightStatus();
}

export async function startPlaywrightMcp(config?: { port?: number; host?: string; cdpEndpoint?: string }): Promise<PlaywrightMcpStatus | null> {
  if (!window.desktopMcp) return null;
  return window.desktopMcp.playwrightStart(config);
}

export async function stopPlaywrightMcp(): Promise<PlaywrightMcpStatus | null> {
  if (!window.desktopMcp) return null;
  return window.desktopMcp.playwrightStop();
}

// ---------- System Prompt ----------

export type TradingMode = 'SCALP' | 'SWING' | 'POSITION';

const TRADING_MODE_GUIDES: Record<TradingMode, string> = {
  SCALP: [
    '## 현재 모드: 초단타 (스캘핑)',
    '- 1분~5분 관점으로 분석한다.',
    '- SL 0.3%, TP 0.8% 수준의 민감한 손절/익절 기준을 적용한다.',
    '- 호가창 변화, 모멘텀 급변, 거래량 스파이크에 집중한다.',
    '- 초 단위 타이밍이 중요하며, 빠른 진입/청산을 권장한다.',
    '- 추세보다 단기 변동성과 주문 흐름에 우선순위를 둔다.',
  ].join('\n'),
  SWING: [
    '## 현재 모드: 단타 (스윙)',
    '- 30분~수시간 관점으로 분석한다.',
    '- SL 0.7%, TP 2.0% 수준의 손절/익절 기준을 적용한다.',
    '- SMA, 볼린저밴드, RSI 등 기술적 지표 기반으로 판단한다.',
    '- 추세 방향과 지지/저항 레벨을 중점적으로 분석한다.',
  ].join('\n'),
  POSITION: [
    '## 현재 모드: 장타 (포지션)',
    '- 일봉 이상의 장기 관점으로 분석한다.',
    '- SL 2.0%, TP 5.0% 수준의 넓은 손절/익절 기준을 적용한다.',
    '- 대형 추세, 주요 지지/저항, 매크로 이벤트를 중점적으로 분석한다.',
    '- 단기 노이즈에 흔들리지 않는 관점을 유지한다.',
  ].join('\n'),
};

function buildSystemPrompt(tradingMode: TradingMode = 'SWING'): string {
  return [
    '너는 수동 코인 트레이딩 보조 AI 에이전트다.',
    '사용자가 제공하는 차트 컨텍스트와 MCP 도구를 활용해 실시간 트레이딩 조언을 제공한다.',
    '',
    TRADING_MODE_GUIDES[tradingMode],
    '',
    '## 역할',
    '- 사용자의 질문에 한국어로 간결하게 답변한다.',
    '- 필요하면 도구를 호출하여 실시간 데이터를 가져온다.',
    '- 분석 완료 시 실행 가능한 액션을 제안한다.',
    '',
    '## 액션 제안 형식 (선택적)',
    '분석 결과에 따라 텍스트 끝에 JSON 블록을 포함할 수 있다:',
    '```json',
    '{"actions":[{"type":"ADD|PARTIAL_TP|FULL_EXIT|HOLD|WAIT_RETEST","title":"짧은제목","reason":"근거","targetPrice":number|null,"sizePercent":number|null,"urgency":"LOW|MEDIUM|HIGH"}]}',
    '```',
    '',
    '## 원칙',
    '- 과도한 확신을 피하고 리스크를 강조한다.',
    '- 데이터 부족 시 솔직하게 한계를 밝힌다.',
    '- 도구 호출 결과를 근거로 활용한다.',
  ].join('\n');
}

function buildUserContent(basePrompt: string, context?: GuidedAgentContextResponse | null): string {
  if (!context) return basePrompt;
  const slicedCandles = context.chart.candles.slice(-60);
  const contextPayload = {
    market: context.market,
    generatedAt: context.generatedAt,
    recommendation: context.chart.recommendation,
    activePosition: context.chart.activePosition,
    events: context.chart.events.slice(-10),
    orderbook: context.chart.orderbook,
    recentClosedTrades: context.recentClosedTrades.slice(0, 5),
    performance: context.performance,
    candlesSummary: {
      count: slicedCandles.length,
      latest: slicedCandles.slice(-3),
      interval: context.chart.interval,
    },
  };
  return `${basePrompt}\n\n[차트 컨텍스트]\n${JSON.stringify(contextPayload)}`;
}

interface CodexResponseEvent {
  type: string;
  item?: {
    type?: string;
    role?: string;
    content?: Array<{ type?: string; text?: string }>;
    name?: string;
    call_id?: string;
    arguments?: string;
  };
  delta?: string;
}

interface ZaiLoopOptions {
  endpointMode: ZaiEndpointMode;
  model: string;
  messages: Array<{ role: 'system' | 'user' | 'assistant'; content: string }>;
  mcpTools?: McpTool[];
  onToolCall?: (toolName: string, args: string) => void;
  onToolResult?: (toolName: string, result: string) => void;
}

async function callZaiChat(payload: DesktopZaiChatPayload): Promise<DesktopZaiChatResponse> {
  if (!window.desktopZai) {
    return { ok: false, error: 'z.ai 데스크톱 IPC를 찾을 수 없습니다.' };
  }
  return withZaiConcurrency(() => window.desktopZai!.chatCompletions(payload));
}

async function runZaiToolLoop(options: ZaiLoopOptions): Promise<ZaiToolLoopResult> {
  const toolMessages: ChatMessage[] = [];
  let accumulatedPromptTokens = 0;
  let accumulatedCompletionTokens = 0;
  let accumulatedTotalTokens = 0;
  const tools = options.mcpTools && options.mcpTools.length > 0
    ? mcpToolsToZaiTools(options.mcpTools)
    : undefined;

  let loops = 0;
  while (loops < 5) {
    loops += 1;
    const response = await callZaiChat({
      endpointMode: options.endpointMode,
      model: options.model,
      messages: options.messages,
      tools,
      toolChoice: tools ? 'auto' : 'none',
    });

    if (!response.ok) {
      throw new Error(response.error || 'z.ai 응답 실패');
    }

    const usage = response.usage;
    if (usage) {
      accumulatedPromptTokens += usage.promptTokens;
      accumulatedCompletionTokens += usage.completionTokens;
      accumulatedTotalTokens += usage.totalTokens;
    }

    const assistantText = (response.text || '').trim();
    const toolCalls = response.toolCalls || [];

    if (toolCalls.length > 0 && options.mcpTools && options.mcpTools.length > 0) {
      if (assistantText) {
        options.messages.push({ role: 'assistant', content: assistantText });
      }

      for (const tc of toolCalls) {
        options.onToolCall?.(tc.name, tc.args);

        let parsedArgs: Record<string, unknown> = {};
        try {
          parsedArgs = JSON.parse(tc.args);
        } catch {
          // keep empty object
        }

        const result = await callMcpTool(tc.name, parsedArgs, options.mcpTools);
        options.onToolResult?.(tc.name, result);

        toolMessages.push({
          id: crypto.randomUUID(),
          role: 'tool',
          content: '',
          timestamp: Date.now(),
          toolCall: { name: tc.name, args: tc.args, result },
        });

        options.messages.push({
          role: 'user',
          content: `[도구 결과: ${tc.name}]\n${result}`,
        });
      }
      continue;
    }

    return {
      text: assistantText,
      toolMessages,
      usage: {
        promptTokens: accumulatedPromptTokens,
        completionTokens: accumulatedCompletionTokens,
        totalTokens: accumulatedTotalTokens,
      },
    };
  }

  return {
    text: '',
    toolMessages,
    usage: {
      promptTokens: accumulatedPromptTokens,
      completionTokens: accumulatedCompletionTokens,
      totalTokens: accumulatedTotalTokens,
    },
  };
}

async function delegateToZaiAgent(options: {
  task: string;
  model?: string;
  endpointMode?: ZaiEndpointMode;
  tradingMode?: TradingMode;
}): Promise<string> {
  const task = options.task.trim();
  if (!task) {
    return JSON.stringify({ error: 'delegate_to_zai_agent: task가 비어 있습니다.' });
  }

  const status = await checkConnection('zai');
  if (status !== 'connected') {
    return JSON.stringify({ error: 'z.ai 미연결 상태입니다. API Key를 먼저 설정하세요.' });
  }

  const delegateMessages: Array<{ role: 'system' | 'user' | 'assistant'; content: string }> = [
    {
      role: 'system',
      content: [
        '너는 OpenAI 상위 에이전트가 위임한 하위 분석 에이전트다.',
        '질문에 직접 답하고, 핵심 근거를 짧게 정리한다.',
        '응답은 한국어로 작성한다.',
      ].join('\n'),
    },
    {
      role: 'user',
      content: task,
    },
  ];

  try {
    const delegated = await runZaiToolLoop({
      endpointMode: options.endpointMode ?? 'coding',
      model: options.model || ZAI_DEFAULT_MODEL,
      messages: delegateMessages,
      mcpTools: undefined,
    });
    return delegated.text || 'z.ai 위임 응답이 비어 있습니다.';
  } catch (error) {
    return JSON.stringify({
      error: error instanceof Error ? error.message : 'z.ai 위임 실패',
    });
  }
}

async function sendOpenAiChatMessage(options: SendChatMessageOptions): Promise<ChatMessage[]> {
  const token = await getOpenAiToken();
  const newMessages: ChatMessage[] = [];

  const userContent = buildUserContent(options.userMessage, options.context);
  const history = conversationHistoryByProvider.openai;
  history.push({ role: 'user', content: userContent });

  newMessages.push({
    id: crypto.randomUUID(),
    role: 'user',
    content: options.userMessage,
    timestamp: Date.now(),
  });

  const input: Array<{ role: string; content: string }> = [];
  for (const msg of history) {
    input.push({ role: msg.role, content: msg.content });
  }

  const openAiTools = options.mcpTools ? mcpToolsToOpenAiFunctions(options.mcpTools) : [];
  const autoDelegationEnabled = isAutoDelegationAllowed(options.delegationMode)
    ? (await checkConnection('zai')) === 'connected'
    : false;
  if (autoDelegationEnabled) {
    openAiTools.push(buildZaiDelegateToolSchema());
  }

  let loopCount = 0;
  const maxLoops = 5;

  while (loopCount < maxLoops) {
    loopCount += 1;

    const requestBody: Record<string, unknown> = {
      model: options.model || 'gpt-5.3-codex',
      instructions: buildSystemPrompt(options.tradingMode || 'SWING'),
      input,
      stream: true,
      store: false,
    };

    if (openAiTools.length > 0) {
      requestBody.tools = openAiTools;
    }

    const headers: Record<string, string> = {
      Authorization: `Bearer ${token.accessToken}`,
      'Content-Type': 'application/json',
      'OpenAI-Beta': 'responses=v1',
    };
    if (token.accountId) {
      headers['ChatGPT-Account-Id'] = token.accountId;
    }

    const response = await fetch(CODEX_API_URL, {
      method: 'POST',
      headers,
      body: JSON.stringify(requestBody),
    });

    if (!response.ok) {
      const errorText = await response.text();
      throw new Error(`Codex API 오류 (${response.status}): ${errorText}`);
    }

    const reader = response.body?.getReader();
    if (!reader) throw new Error('응답 스트림을 읽을 수 없습니다.');

    const decoder = new TextDecoder();
    let buffer = '';
    let accumulated = '';
    const pendingToolCalls: Array<{ callId: string; name: string; args: string }> = [];
    let hasToolCalls = false;

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (!line.startsWith('data: ')) continue;
        const data = line.slice(6).trim();
        if (data === '[DONE]') continue;

        let event: CodexResponseEvent;
        try {
          event = JSON.parse(data);
        } catch {
          continue;
        }

        if (event.type === 'response.output_text.delta' && event.delta) {
          accumulated += event.delta;
          options.onStreamDelta?.(accumulated);
        }

        if (event.type === 'response.output_item.done' && event.item?.type === 'function_call') {
          hasToolCalls = true;
          pendingToolCalls.push({
            callId: event.item.call_id || '',
            name: event.item.name || '',
            args: event.item.arguments || '{}',
          });
        }
      }
    }

    if (hasToolCalls && pendingToolCalls.length > 0) {
      if (accumulated.trim()) {
        input.push({ role: 'assistant', content: accumulated });
      }

      for (const tc of pendingToolCalls) {
        options.onToolCall?.(tc.name, tc.args);

        let parsedArgs: Record<string, unknown> = {};
        try {
          parsedArgs = JSON.parse(tc.args);
        } catch {
          // keep empty object
        }

        let result: string;
        if (tc.name === ZAI_DELEGATE_TOOL_NAME) {
          const task = typeof parsedArgs.task === 'string' ? parsedArgs.task : '';
          const delegatedModel = typeof parsedArgs.model === 'string' && parsedArgs.model.trim()
            ? parsedArgs.model.trim()
            : (options.zaiDelegateModel || ZAI_DEFAULT_MODEL);
          result = await delegateToZaiAgent({
            task,
            model: delegatedModel,
            endpointMode: options.zaiEndpointMode,
            tradingMode: options.tradingMode,
          });
        } else {
          result = await callMcpTool(tc.name, parsedArgs, options.mcpTools);
        }

        options.onToolResult?.(tc.name, result);

        newMessages.push({
          id: crypto.randomUUID(),
          role: 'tool',
          content: '',
          timestamp: Date.now(),
          toolCall: { name: tc.name, args: tc.args, result },
        });

        input.push({
          role: 'user',
          content: `[도구 결과: ${tc.name}]\n${result}`,
        });
      }

      accumulated = '';
      continue;
    }

    if (accumulated.trim()) {
      history.push({ role: 'assistant', content: accumulated });
      const parsedActions = extractActions(accumulated);
      newMessages.push({
        id: crypto.randomUUID(),
        role: 'assistant',
        content: accumulated,
        timestamp: Date.now(),
        actions: parsedActions.length > 0 ? parsedActions : undefined,
      });
    }

    break;
  }

  return newMessages;
}

async function sendZaiChatMessage(options: SendChatMessageOptions): Promise<ChatMessage[]> {
  const status = await checkConnection('zai');
  if (status !== 'connected') {
    throw new Error('z.ai 미연결 상태입니다. API Key를 먼저 등록하세요.');
  }

  const newMessages: ChatMessage[] = [];
  const userContent = buildUserContent(options.userMessage, options.context);
  const history = conversationHistoryByProvider.zai;
  history.push({ role: 'user', content: userContent });

  newMessages.push({
    id: crypto.randomUUID(),
    role: 'user',
    content: options.userMessage,
    timestamp: Date.now(),
  });

  const messages: Array<{ role: 'system' | 'user' | 'assistant'; content: string }> = [
    { role: 'system', content: buildSystemPrompt(options.tradingMode || 'SWING') },
    ...history.map((message) => ({ role: message.role, content: message.content })),
  ];

  const loopResult = await runZaiToolLoop({
    endpointMode: options.zaiEndpointMode ?? 'coding',
    model: options.model || ZAI_DEFAULT_MODEL,
    messages,
    mcpTools: options.mcpTools,
    onToolCall: options.onToolCall,
    onToolResult: options.onToolResult,
  });

  newMessages.push(...loopResult.toolMessages);
  if (loopResult.text) {
    options.onStreamDelta?.(loopResult.text);
    history.push({ role: 'assistant', content: loopResult.text });
    const parsedActions = extractActions(loopResult.text);
    newMessages.push({
      id: crypto.randomUUID(),
      role: 'assistant',
      content: loopResult.text,
      timestamp: Date.now(),
      actions: parsedActions.length > 0 ? parsedActions : undefined,
    });
  }

  return newMessages;
}

// ---------- Main: Send Chat Message ----------

export async function sendChatMessage(options: SendChatMessageOptions): Promise<ChatMessage[]> {
  const provider = resolveProvider(options.provider);
  if (provider === 'zai') {
    return sendZaiChatMessage(options);
  }
  return sendOpenAiChatMessage(options);
}

// ---------- One-shot ----------

export async function requestOneShotText(options: OneShotOptions): Promise<string> {
  const result = await requestOneShotTextWithMeta(options);
  return result.text;
}

async function requestOpenAiOneShotWithMeta(options: OneShotOptions): Promise<{ text: string; usage: OneShotUsageMeta }> {
  const token = await getOpenAiToken();

  const userContent = buildUserContent(options.prompt, options.context);
  const estimatedInputTokens = estimateTextTokens(userContent);

  const requestBody: Record<string, unknown> = {
    model: options.model || 'gpt-5.3-codex',
    instructions: buildSystemPrompt(options.tradingMode || 'SWING'),
    input: [{ role: 'user', content: userContent }],
    stream: true,
    store: false,
  };

  const openAiTools = options.mcpTools ? mcpToolsToOpenAiFunctions(options.mcpTools) : [];
  if (openAiTools.length > 0) {
    requestBody.tools = openAiTools;
  }

  const headers: Record<string, string> = {
    Authorization: `Bearer ${token.accessToken}`,
    'Content-Type': 'application/json',
    'OpenAI-Beta': 'responses=v1',
  };
  if (token.accountId) {
    headers['ChatGPT-Account-Id'] = token.accountId;
  }

  const response = await fetch(CODEX_API_URL, {
    method: 'POST',
    headers,
    body: JSON.stringify(requestBody),
  });
  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`Codex API 오류 (${response.status}): ${errorText}`);
  }

  const reader = response.body?.getReader();
  if (!reader) throw new Error('응답 스트림을 읽을 수 없습니다.');

  const decoder = new TextDecoder();
  let buffer = '';
  let accumulated = '';
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop() || '';

    for (const line of lines) {
      if (!line.startsWith('data: ')) continue;
      const data = line.slice(6).trim();
      if (data === '[DONE]') continue;

      let event: CodexResponseEvent;
      try {
        event = JSON.parse(data);
      } catch {
        continue;
      }
      if (event.type === 'response.output_text.delta' && event.delta) {
        accumulated += event.delta;
      }
    }
  }

  const text = accumulated.trim();
  const estimatedOutputTokens = estimateTextTokens(text);
  return {
    text,
    usage: {
      estimatedInputTokens,
      estimatedOutputTokens,
      totalTokens: estimatedInputTokens + estimatedOutputTokens,
    },
  };
}

async function requestZaiOneShotWithMeta(options: OneShotOptions): Promise<{ text: string; usage: OneShotUsageMeta }> {
  const status = await checkConnection('zai');
  if (status !== 'connected') {
    throw new Error('z.ai 미연결 상태입니다. API Key를 먼저 등록하세요.');
  }

  const userContent = buildUserContent(options.prompt, options.context);
  const estimatedInputTokens = estimateTextTokens(userContent);
  const messages: Array<{ role: 'system' | 'user' | 'assistant'; content: string }> = [
    { role: 'system', content: buildSystemPrompt(options.tradingMode || 'SWING') },
    { role: 'user', content: userContent },
  ];

  const loopResult = await runZaiToolLoop({
    endpointMode: options.zaiEndpointMode ?? 'coding',
    model: options.model || ZAI_DEFAULT_MODEL,
    messages,
    mcpTools: options.mcpTools,
  });

  const usage = loopResult.usage.totalTokens > 0
    ? {
      estimatedInputTokens: Math.max(1, loopResult.usage.promptTokens),
      estimatedOutputTokens: Math.max(1, loopResult.usage.completionTokens),
      totalTokens: Math.max(1, loopResult.usage.totalTokens),
    }
    : {
      estimatedInputTokens,
      estimatedOutputTokens: estimateTextTokens(loopResult.text),
      totalTokens: estimatedInputTokens + estimateTextTokens(loopResult.text),
    };

  return {
    text: loopResult.text,
    usage,
  };
}

export async function requestOneShotTextWithMeta(options: OneShotOptions): Promise<{ text: string; usage: OneShotUsageMeta }> {
  const provider = resolveProvider(options.provider);
  if (provider === 'zai') {
    return requestZaiOneShotWithMeta(options);
  }
  return requestOpenAiOneShotWithMeta(options);
}

function estimateTextTokens(text: string): number {
  const chars = typeof text === 'string' ? text.length : 0;
  return Math.max(1, Math.ceil(chars / 4));
}

// ---------- Action Extraction ----------

function extractActions(text: string): AgentAction[] {
  try {
    const codeBlockMatch = text.match(/```json\s*([\s\S]*?)```/);
    const jsonStr = codeBlockMatch ? codeBlockMatch[1] : null;

    if (!jsonStr) {
      const inlineMatch = text.match(/\{"actions"\s*:\s*\[[\s\S]*?\]\s*\}/);
      if (!inlineMatch) return [];
      const parsed = JSON.parse(inlineMatch[0]);
      return normalizeActions(parsed.actions);
    }

    const parsed = JSON.parse(jsonStr);
    if (parsed.actions) return normalizeActions(parsed.actions);
    return [];
  } catch {
    return [];
  }
}

function normalizeActions(raw: unknown[]): AgentAction[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((item: unknown) => {
    const a = (item ?? {}) as Record<string, unknown>;
    return {
      type: typeof a.type === 'string' ? a.type : 'HOLD',
      title: typeof a.title === 'string' ? a.title : '관망',
      reason: typeof a.reason === 'string' ? a.reason : '',
      targetPrice: typeof a.targetPrice === 'number' ? a.targetPrice : null,
      sizePercent: typeof a.sizePercent === 'number' ? a.sizePercent : null,
      urgency: typeof a.urgency === 'string' ? a.urgency : 'MEDIUM',
    };
  });
}

// ---------- Legacy: parseAdvice ----------

export function parseAdvice(raw: string): AgentAdvice {
  try {
    const start = raw.indexOf('{');
    const end = raw.lastIndexOf('}');
    const jsonStr = start >= 0 && end > start ? raw.substring(start, end + 1) : raw;
    const parsed = JSON.parse(jsonStr) as Partial<AgentAdvice>;
    return {
      analysis: typeof parsed.analysis === 'string' ? parsed.analysis : raw,
      confidence: typeof parsed.confidence === 'number' ? Math.max(0, Math.min(100, parsed.confidence)) : 50,
      actions: Array.isArray(parsed.actions)
        ? parsed.actions.map((a) => ({
          type: typeof a?.type === 'string' ? a.type : 'HOLD',
          title: typeof a?.title === 'string' ? a.title : '관망',
          reason: typeof a?.reason === 'string' ? a.reason : '시장 노이즈 구간',
          targetPrice: typeof a?.targetPrice === 'number' ? a.targetPrice : null,
          sizePercent: typeof a?.sizePercent === 'number' ? a.sizePercent : null,
          urgency: typeof a?.urgency === 'string' ? a.urgency : 'MEDIUM',
        }))
        : [],
    };
  } catch {
    return { analysis: raw, confidence: 50, actions: [] };
  }
}
