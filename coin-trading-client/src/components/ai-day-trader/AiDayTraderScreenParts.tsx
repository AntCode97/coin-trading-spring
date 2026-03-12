import type { ReactNode } from 'react';
import type { GuidedTradePosition } from '../../api';
import type {
  AiRankedOpportunity,
  AiTraderEvent,
  AiTraderState,
} from '../../lib/ai-day-trader/AiDayTraderModel';
import type { LlmConnectionStatus } from '../../lib/llmService';

export function statusLabel(status: AiTraderState['status']): string {
  const labels: Record<AiTraderState['status'], string> = {
    IDLE: '대기',
    SCANNING: '스캔 중',
    RANKING: '숏리스트 랭킹',
    ANALYZING: '최종 진입 검토',
    EXECUTING: '주문 실행',
    PAUSED: '신규 진입 차단',
    ERROR: '오류',
  };
  return labels[status];
}

export function statusToneClass(status: AiTraderState['status']): string {
  if (status === 'EXECUTING') return 'is-live';
  if (status === 'SCANNING' || status === 'RANKING' || status === 'ANALYZING') return 'is-busy';
  if (status === 'PAUSED') return 'is-paused';
  if (status === 'ERROR') return 'is-error';
  return 'is-idle';
}

export function providerStatusLabel(status: LlmConnectionStatus): string {
  if (status === 'connected') return '연결됨';
  if (status === 'checking') return '확인 중';
  if (status === 'expired') return '만료';
  if (status === 'error') return '오류';
  return '미연결';
}

export function formatKrw(value: number): string {
  const sign = value > 0 ? '+' : '';
  return `${sign}${Math.round(value).toLocaleString()}원`;
}

export function formatPercent(value: number): string {
  const sign = value > 0 ? '+' : '';
  return `${sign}${value.toFixed(2)}%`;
}

export function formatCompactNumber(value: number): string {
  return new Intl.NumberFormat('ko-KR', {
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(value);
}

export function formatClock(timestamp: number): string {
  return new Date(timestamp).toLocaleTimeString('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

export function formatDateTime(value: string): string {
  return new Date(value).toLocaleTimeString('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

export function formatHoldingMinutes(createdAt: string, closedAt?: string | null): string {
  const start = new Date(createdAt).getTime();
  const end = new Date(closedAt ?? createdAt).getTime();
  const minutes = Math.max(0, (end - start) / 60_000);
  return `${minutes.toFixed(1)}분`;
}

export function formatExitReason(value?: string | null): string {
  const normalized = value?.trim().toUpperCase();
  if (!normalized) return '-';

  const labels: Record<string, string> = {
    MANUAL_STOP: '수동/기존 청산',
    MANUAL_STOP_ESTIMATED: '추정 수동 청산',
    STOP_LOSS: '손절',
    AI_STOP_LOSS: 'AI 하드 손절',
    AI_LLM_EXIT: 'AI 판단 청산',
    AI_TIME_STOP: '시간 청산',
    AI_CONTEXT_FAIL: '컨텍스트 실패 청산',
    AI_FORCED_EXIT: '강제 청산',
    AI_STALE_LOSER: '정체 손실 청산',
    AI_STALE_FLAT: '정체 시간 청산',
    AI_PROFIT_FADE: '이익 반납 방지',
    AI_PROFIT_EXIT: 'AI 익절',
  };

  return labels[normalized] ?? normalized.replaceAll('_', ' ');
}

export function PanelHeader({ title, subtitle, right }: { title: string; subtitle: string; right?: string }) {
  return (
    <div className="ai-scalp-panel__header">
      <div>
        <div className="ai-scalp-panel__title">{title}</div>
        <div className="ai-scalp-panel__subtitle">{subtitle}</div>
      </div>
      {right && <div className="ai-scalp-panel__right">{right}</div>}
    </div>
  );
}

export function KpiCard({ label, value, tone }: { label: string; value: string; tone?: 'positive' | 'negative' }) {
  return (
    <div className="ai-scalp-kpi">
      <div className="ai-scalp-kpi__label">{label}</div>
      <div className={`ai-scalp-kpi__value ${tone ?? ''}`}>{value}</div>
    </div>
  );
}

export function MiniStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="ai-scalp-mini-stat">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

export function ConfigField({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="ai-scalp-config__field">
      <span>{label}</span>
      {children}
    </label>
  );
}

export function OpportunityCard({ opportunity, rank }: { opportunity: AiRankedOpportunity; rank: number }) {
  return (
    <article className="ai-scalp-opportunity">
      <div className="ai-scalp-opportunity__header">
        <span className="ai-scalp-opportunity__rank">#{rank}</span>
        <div>
          <div className="ai-scalp-opportunity__market">{opportunity.market.replace('KRW-', '')}</div>
          <div className="ai-scalp-opportunity__name">{opportunity.koreanName}</div>
        </div>
        <div className="ai-scalp-opportunity__score">{opportunity.score.toFixed(0)}</div>
      </div>
      <div className="ai-scalp-opportunity__meta">
        <span>가격 {opportunity.tradePrice.toLocaleString()}</span>
        <span className={opportunity.changeRate >= 0 ? 'positive' : 'negative'}>
          변화 {formatPercent(opportunity.changeRate)}
        </span>
        <span>거래대금 {formatCompactNumber(opportunity.turnover)}</span>
      </div>
      <div className="ai-scalp-opportunity__reason">{opportunity.reason}</div>
      {opportunity.crowd && (
        <div className="ai-scalp-opportunity__chips">
          <span>flow {opportunity.crowd.flowScore.toFixed(1)}</span>
          <span>spike {opportunity.crowd.priceSpike10sPercent.toFixed(2)}%</span>
          <span>imb {opportunity.crowd.bidImbalance.toFixed(2)}</span>
          <span>spread {opportunity.crowd.spreadPercent.toFixed(2)}%</span>
        </div>
      )}
    </article>
  );
}

export function JournalItem({ event }: { event: AiTraderEvent }) {
  return (
    <article className="ai-scalp-journal__item">
      <div className="ai-scalp-journal__rail" />
      <div className="ai-scalp-journal__content">
        <div className="ai-scalp-journal__header">
          <div className="ai-scalp-journal__headline">
            <span className={`ai-scalp-event-badge ${event.type.toLowerCase()}`}>{event.type}</span>
            <span>{event.message}</span>
          </div>
          <div className="ai-scalp-journal__time">{formatClock(event.timestamp)}</div>
        </div>
        {(event.market || event.confidence !== undefined || event.urgency) && (
          <div className="ai-scalp-journal__meta">
            {event.market && <span>{event.market}</span>}
            {event.confidence !== undefined && <span>confidence {(event.confidence * 100).toFixed(0)}%</span>}
            {event.urgency && <span>urgency {event.urgency}</span>}
          </div>
        )}
        {event.detail && <div className="ai-scalp-journal__detail">{event.detail}</div>}
      </div>
    </article>
  );
}

export function PositionCard({ position }: { position: GuidedTradePosition }) {
  const holdingMinutes = Math.max(0, (Date.now() - new Date(position.createdAt).getTime()) / 60_000);
  const pnlClass = position.unrealizedPnlPercent >= 0 ? 'positive' : 'negative';

  return (
    <article className="ai-scalp-position">
      <div className="ai-scalp-position__header">
        <div>
          <div className="ai-scalp-position__market">{position.market.replace('KRW-', '')}</div>
          <div className="ai-scalp-position__strategy">
            {position.positionSide === 'SHORT' ? 'SHORT' : 'LONG'}
            {' · '}
            {position.executionVenue === 'BINANCE_FUTURES' ? 'BINANCE' : 'BITHUMB'}
            {' · '}
            {position.strategyCode ?? 'AI_SCALP_TRADER'}
          </div>
        </div>
        <div className={`ai-scalp-position__pnl ${pnlClass}`}>
          {formatPercent(position.unrealizedPnlPercent)}
        </div>
      </div>
      <div className="ai-scalp-position__grid">
        <span>보유시간</span>
        <strong>{holdingMinutes.toFixed(1)}분</strong>
        <span>진입가</span>
        <strong>{position.averageEntryPrice.toLocaleString()}</strong>
        <span>현재가</span>
        <strong>{position.currentPrice.toLocaleString()}</strong>
        <span>손절</span>
        <strong>{position.stopLossPrice.toLocaleString()}</strong>
        <span>익절</span>
        <strong>{position.takeProfitPrice.toLocaleString()}</strong>
      </div>
    </article>
  );
}

export function EmptyState({ title, description }: { title: string; description: string }) {
  return (
    <div className="ai-scalp-empty">
      <div className="ai-scalp-empty__title">{title}</div>
      <div className="ai-scalp-empty__description">{description}</div>
    </div>
  );
}
