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

export type ChatMessage = {
  id: string;
  role: 'user' | 'assistant' | 'system' | 'tool';
  content: string;
  timestamp: number;
  toolCall?: { name: string; args: string; result?: string };
  actions?: AgentAction[];
};

// ---------- Codex Backend API ----------

const CODEX_API_URL = 'https://chatgpt.com/backend-api/codex/responses';

let cachedToken: { accessToken: string; accountId?: string | null } | null = null;

async function getToken(): Promise<{ accessToken: string; accountId?: string | null }> {
  if (cachedToken) return cachedToken;
  const result = await window.desktopAuth?.getToken();
  if (!result?.accessToken) throw new Error('OpenAI 인증이 필요합니다. 로그인을 먼저 진행하세요.');
  cachedToken = result;
  return cachedToken;
}

export function clearClient(): void {
  cachedToken = null;
}

// ---------- Connection ----------

export async function checkConnection(): Promise<LlmConnectionStatus> {
  if (!window.desktopAuth) return 'disconnected';
  try {
    const result = await window.desktopAuth.checkStatus();
    return result.status;
  } catch {
    return 'error';
  }
}

export async function startLogin(): Promise<void> {
  if (!window.desktopAuth) throw new Error('데스크톱 앱에서만 사용 가능합니다.');
  await window.desktopAuth.startLogin();
  clearClient();
}

export async function logout(): Promise<void> {
  if (!window.desktopAuth) return;
  await window.desktopAuth.logout();
  clearClient();
}

// ---------- MCP 도구 → OpenAI Function Schema ----------

function mcpToolsToOpenAiFunctions(tools: McpTool[]): object[] {
  return tools.map((tool) => ({
    type: 'function',
    name: tool.name,
    description: tool.description || tool.name,
    parameters: tool.inputSchema || { type: 'object', properties: {} },
  }));
}

// ---------- MCP 연결 ----------

export async function connectMcp(mcpUrl: string): Promise<McpTool[]> {
  if (!window.desktopMcp) return [];
  const result = await window.desktopMcp.connect(mcpUrl);
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

async function callMcpTool(name: string, args: Record<string, unknown>): Promise<string> {
  if (!window.desktopMcp) return JSON.stringify({ error: 'MCP 미연결' });
  const result = await window.desktopMcp.callTool(name, args);
  if (result.isError) {
    return JSON.stringify({ error: result.content?.[0]?.text || '도구 실행 실패' });
  }
  // MCP content 배열에서 텍스트 추출
  const texts = (result.content || [])
    .filter((c) => c.type === 'text' && c.text)
    .map((c) => c.text);
  return texts.join('\n') || JSON.stringify(result);
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

// ---------- Conversation History ----------

type ConversationMessage =
  | { role: 'user'; content: string }
  | { role: 'assistant'; content: string };

let conversationHistory: ConversationMessage[] = [];

export function clearConversation(): void {
  conversationHistory = [];
}

// ---------- SSE Streaming Parser ----------

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
  output_index?: number;
  content_index?: number;
}

// ---------- Available Models ----------

export const CODEX_MODELS = [
  { id: 'gpt-5.3-codex', label: 'GPT-5.3 Codex' },
  { id: 'o3', label: 'o3' },
  { id: 'o4-mini', label: 'o4-mini' },
  { id: 'gpt-4.1', label: 'GPT-4.1' },
  { id: 'gpt-4.1-mini', label: 'GPT-4.1 mini' },
] as const;

export type CodexModelId = (typeof CODEX_MODELS)[number]['id'];

// ---------- Main: Send Chat Message (Streaming + Tool Loop) ----------

export async function sendChatMessage(options: {
  userMessage: string;
  model?: string;
  context?: GuidedAgentContextResponse | null;
  mcpTools?: McpTool[];
  tradingMode?: TradingMode;
  onStreamDelta?: (accumulated: string) => void;
  onToolCall?: (toolName: string, args: string) => void;
  onToolResult?: (toolName: string, result: string) => void;
}): Promise<ChatMessage[]> {
  const token = await getToken();
  const newMessages: ChatMessage[] = [];

  // 컨텍스트가 있으면 사용자 메시지에 첨부
  let userContent = options.userMessage;
  if (options.context) {
    const slicedCandles = options.context.chart.candles.slice(-60);
    const contextPayload = {
      market: options.context.market,
      generatedAt: options.context.generatedAt,
      recommendation: options.context.chart.recommendation,
      activePosition: options.context.chart.activePosition,
      events: options.context.chart.events.slice(-10),
      orderbook: options.context.chart.orderbook,
      recentClosedTrades: options.context.recentClosedTrades.slice(0, 5),
      performance: options.context.performance,
      candlesSummary: {
        count: slicedCandles.length,
        latest: slicedCandles.slice(-3),
        interval: options.context.chart.interval,
      },
    };
    userContent = `${options.userMessage}\n\n[차트 컨텍스트]\n${JSON.stringify(contextPayload)}`;
  }

  conversationHistory.push({ role: 'user', content: userContent });

  const userMsg: ChatMessage = {
    id: crypto.randomUUID(),
    role: 'user',
    content: options.userMessage,
    timestamp: Date.now(),
  };
  newMessages.push(userMsg);

  // Codex API 입력 구성
  const input: Array<{ role: string; content: string }> = [];
  for (const msg of conversationHistory) {
    input.push({ role: msg.role, content: msg.content });
  }

  const openAiTools = options.mcpTools ? mcpToolsToOpenAiFunctions(options.mcpTools) : [];

  // 도구 호출 루프 (최대 5회)
  let loopCount = 0;
  const maxLoops = 5;

  while (loopCount < maxLoops) {
    loopCount++;

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
      'Authorization': `Bearer ${token.accessToken}`,
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

    // 도구 호출이 있으면 MCP로 실행 후 루프 계속
    if (hasToolCalls && pendingToolCalls.length > 0) {
      if (accumulated.trim()) {
        input.push({ role: 'assistant', content: accumulated });
      }

      for (const tc of pendingToolCalls) {
        options.onToolCall?.(tc.name, tc.args);

        let parsedArgs: Record<string, unknown> = {};
        try {
          parsedArgs = JSON.parse(tc.args);
        } catch { /* empty */ }

        // MCP를 통해 도구 실행
        const result = await callMcpTool(tc.name, parsedArgs);
        options.onToolResult?.(tc.name, result);

        const toolMsg: ChatMessage = {
          id: crypto.randomUUID(),
          role: 'tool',
          content: '',
          timestamp: Date.now(),
          toolCall: { name: tc.name, args: tc.args, result },
        };
        newMessages.push(toolMsg);

        input.push({
          role: 'user',
          content: `[도구 결과: ${tc.name}]\n${result}`,
        });
      }

      accumulated = '';
      continue;
    }

    // 텍스트만 있으면 루프 종료
    if (accumulated.trim()) {
      conversationHistory.push({ role: 'assistant', content: accumulated });

      const parsedActions = extractActions(accumulated);
      const assistantMsg: ChatMessage = {
        id: crypto.randomUUID(),
        role: 'assistant',
        content: accumulated,
        timestamp: Date.now(),
        actions: parsedActions.length > 0 ? parsedActions : undefined,
      };
      newMessages.push(assistantMsg);
    }

    break;
  }

  return newMessages;
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

// ---------- Legacy: parseAdvice (하위 호환) ----------

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
