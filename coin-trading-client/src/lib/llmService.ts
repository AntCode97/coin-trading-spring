import OpenAI from 'openai';
import { getApiBaseUrl, type GuidedAgentContextResponse } from '../api';

// ---------- Types (기존 opencodeSession.ts에서 이관) ----------

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

// ---------- OpenAI Client ----------

let cachedClient: OpenAI | null = null;

async function getClient(): Promise<OpenAI> {
  if (cachedClient) return cachedClient;
  const result = await window.desktopAuth?.getToken();
  if (!result?.accessToken) throw new Error('OpenAI 인증이 필요합니다. 로그인을 먼저 진행하세요.');
  cachedClient = new OpenAI({
    apiKey: result.accessToken,
    dangerouslyAllowBrowser: true,
  });
  return cachedClient;
}

export function clearClient(): void {
  cachedClient = null;
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

// ---------- System Prompt ----------

function buildSystemPrompt(): string {
  return [
    '너는 수동 코인 트레이딩 보조 에이전트다.',
    '아래 JSON 컨텍스트를 분석해서 현재 시점 조언을 제공해라.',
    '반드시 JSON으로만 응답하고 아래 스키마를 지켜라.',
    '{"analysis":"2-4문장","confidence":0-100,"actions":[{"type":"ADD|PARTIAL_TP|FULL_EXIT|HOLD|WAIT_RETEST","title":"짧은제목","reason":"근거","targetPrice":number|null,"sizePercent":number|null,"urgency":"LOW|MEDIUM|HIGH"}]}',
    '캔들/체결 이력이 부족하면 제한사항을 analysis에 먼저 밝히고, 과도한 확신을 피하라.',
    '리스크 과대 노출을 피하고, 근거 없는 확신을 금지한다.',
  ].join('\n');
}

// ---------- User Message Builder ----------

function buildUserMessage(context: GuidedAgentContextResponse, userPrompt: string): string {
  const slicedCandles = context.chart.candles.slice(-120);
  const contextHealth = {
    hasOrderbook: Boolean(context.chart.orderbook),
    hasPosition: Boolean(context.chart.activePosition),
    eventCount: context.chart.events.length,
    closedTradeCount: context.recentClosedTrades.length,
    candleCount: slicedCandles.length,
  };
  const payload = {
    market: context.market,
    generatedAt: context.generatedAt,
    recommendation: context.chart.recommendation,
    activePosition: context.chart.activePosition,
    events: context.chart.events.slice(-20),
    orderbook: context.chart.orderbook,
    orderSnapshot: context.chart.orderSnapshot,
    recentClosedTrades: context.recentClosedTrades,
    performance: context.performance,
    candles: slicedCandles,
    contextHealth,
  };

  const apiBaseUrl = getApiBaseUrl().replace(/\/$/, '');

  return [
    '추가 데이터가 필요하면 다음 Spring API를 참고해라:',
    `- GET ${apiBaseUrl}/guided-trading/agent/context?market=${encodeURIComponent(context.market)}&interval=${encodeURIComponent(context.chart.interval)}&count=120&closedTradeLimit=20`,
    `- GET ${apiBaseUrl}/guided-trading/chart?market=${encodeURIComponent(context.market)}&interval=${encodeURIComponent(context.chart.interval)}&count=120`,
    `- GET ${apiBaseUrl}/dashboard`,
    userPrompt.trim().length > 0 ? `사용자 추가지시: ${userPrompt.trim()}` : '사용자 추가지시: 없음',
    `컨텍스트 JSON: ${JSON.stringify(payload)}`,
  ].join('\n');
}

// ---------- Advice Parser ----------

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

// ---------- Main: Request Advice (Streaming) ----------

export async function requestAdvice(options: {
  context: GuidedAgentContextResponse;
  userPrompt: string;
  onStreamDelta?: (accumulated: string) => void;
}): Promise<AgentAdvice> {
  const client = await getClient();

  const stream = await client.chat.completions.create({
    model: 'gpt-4o',
    messages: [
      { role: 'system', content: buildSystemPrompt() },
      { role: 'user', content: buildUserMessage(options.context, options.userPrompt) },
    ],
    response_format: { type: 'json_object' },
    temperature: 0.3,
    max_tokens: 1500,
    stream: true,
  });

  let accumulated = '';
  let rafId: number | null = null;
  let pendingFlush = '';

  for await (const chunk of stream) {
    const delta = chunk.choices[0]?.delta?.content;
    if (!delta) continue;
    accumulated += delta;
    pendingFlush += delta;

    if (options.onStreamDelta && !rafId) {
      rafId = requestAnimationFrame(() => {
        options.onStreamDelta!(accumulated);
        pendingFlush = '';
        rafId = null;
      });
    }
  }

  // Final flush
  if (rafId) cancelAnimationFrame(rafId);
  if (options.onStreamDelta) options.onStreamDelta(accumulated);

  return parseAdvice(accumulated);
}
