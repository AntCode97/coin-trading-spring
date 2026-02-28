import { useEffect, useMemo, useState } from 'react';
import type { AutopilotTimelineEvent } from '../../lib/autopilot/AutopilotOrchestrator';
import './FocusedScalpLiveDock.css';

export type FocusedScalpEntryState = 'ENTERED' | 'PENDING' | 'NO_ENTRY' | 'NONE';
export type FocusedScalpManageState = 'TAKE_PROFIT' | 'STOP_LOSS' | 'HOLD' | 'SUPERVISION' | 'NONE';

export interface FocusedScalpDecisionCardView {
  market: string;
  koreanName: string;
  workerStatus: string;
  workerNote: string;
  positionStatus: string;
  entryState: FocusedScalpEntryState;
  entryLabel: string;
  entryDetail: string;
  entryAt: number | null;
  manageState: FocusedScalpManageState;
  manageLabel: string;
  manageDetail: string;
  manageAt: number | null;
  recentEvents: AutopilotTimelineEvent[];
}

export interface FocusedScalpDecisionSummaryView {
  entered: number;
  pending: number;
  noEntry: number;
  takeProfit: number;
  stopLoss: number;
  hold: number;
  supervision: number;
}

interface FocusedScalpLiveDockProps {
  open: boolean;
  collapsed: boolean;
  onToggleCollapse: () => void;
  enabled: boolean;
  cards: FocusedScalpDecisionCardView[];
  summary: FocusedScalpDecisionSummaryView;
  onSelectMarket?: (market: string) => void;
}

function formatTs(epochMillis: number): string {
  return new Date(epochMillis).toLocaleTimeString('ko-KR', {
    timeZone: 'Asia/Seoul',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

function toClassToken(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '_');
}

export function FocusedScalpLiveDock({
  open,
  collapsed,
  onToggleCollapse,
  enabled,
  cards,
  summary,
  onSelectMarket,
}: FocusedScalpLiveDockProps) {
  const [selectedMarket, setSelectedMarket] = useState<string | null>(null);

  useEffect(() => {
    if (!cards.length) {
      setSelectedMarket(null);
      return;
    }
    if (!selectedMarket || !cards.some((card) => card.market === selectedMarket)) {
      setSelectedMarket(cards[0].market);
    }
  }, [cards, selectedMarket]);

  const selectedCard = useMemo(
    () => cards.find((card) => card.market === selectedMarket) ?? null,
    [cards, selectedMarket]
  );

  if (!open) return null;

  return (
    <section className={`focused-live-dock ${collapsed ? 'collapsed' : ''}`}>
      <div className="focused-live-header">
        <div>
          <strong>선택 단타 라이브 도크</strong>
          <span>
            {enabled
              ? `활성 코인 ${cards.length}개 · 진입 ${summary.entered} · 미진입 ${summary.noEntry}`
              : '선택 단타 루프 비활성'}
          </span>
        </div>
        <button type="button" onClick={onToggleCollapse}>
          {collapsed ? '열기' : '접기'}
        </button>
      </div>

      {!collapsed && (
        <>
          <div className="focused-live-summary">
            <span className="chip entered">진입 {summary.entered}</span>
            <span className="chip pending">대기 {summary.pending}</span>
            <span className="chip no-entry">미진입 {summary.noEntry}</span>
            <span className="chip tp">익절 {summary.takeProfit}</span>
            <span className="chip sl">손절/청산 {summary.stopLoss}</span>
            <span className="chip hold">유지 {summary.hold}</span>
            <span className="chip supervision">감시 {summary.supervision}</span>
          </div>
          <div className="focused-live-body">
            <div className="focused-live-column card-list">
              <div className="column-title">코인별 판단 ({cards.length})</div>
              {!enabled ? (
                <div className="empty">선택 단타 루프가 꺼져 있습니다.</div>
              ) : cards.length === 0 ? (
                <div className="empty">선택 코인을 등록하면 여기서 코인별 판단을 크게 볼 수 있습니다.</div>
              ) : (
                <div className="focused-card-list">
                  {cards.map((card) => (
                    <button
                      key={card.market}
                      type="button"
                      className={`focused-card ${selectedMarket === card.market ? 'active' : ''}`}
                      onClick={() => {
                        setSelectedMarket(card.market);
                        onSelectMarket?.(card.market);
                      }}
                    >
                      <div className="focused-card-top">
                        <div>
                          <strong>{card.koreanName}</strong>
                          <span>{card.market}</span>
                        </div>
                        <div className="status-wrap">
                          <span className="worker">{card.workerStatus}</span>
                          <span className={`position ${toClassToken(card.positionStatus)}`}>
                            {card.positionStatus}
                          </span>
                        </div>
                      </div>
                      <div className="focused-card-lines">
                        <div>
                          <label>진입</label>
                          <span className={`state ${toClassToken(card.entryState)}`}>{card.entryLabel}</span>
                          <small>{card.entryAt ? formatTs(card.entryAt) : '-'}</small>
                        </div>
                        <p>{card.entryDetail}</p>
                      </div>
                      <div className="focused-card-lines">
                        <div>
                          <label>관리</label>
                          <span className={`state ${toClassToken(card.manageState)}`}>{card.manageLabel}</span>
                          <small>{card.manageAt ? formatTs(card.manageAt) : '-'}</small>
                        </div>
                        <p>{card.manageDetail}</p>
                      </div>
                      <small className="worker-note">{card.workerNote}</small>
                    </button>
                  ))}
                </div>
              )}
            </div>

            <div className="focused-live-column timeline">
              <div className="column-title">
                {selectedCard ? `${selectedCard.market} 최근 실행 이벤트` : '실행 이벤트'}
              </div>
              {!selectedCard ? (
                <div className="empty">코인을 선택하세요.</div>
              ) : selectedCard.recentEvents.length === 0 ? (
                <div className="empty">최근 이벤트가 없습니다.</div>
              ) : (
                <div className="focused-event-list">
                  {selectedCard.recentEvents.map((event) => (
                    <div key={event.id} className="focused-event-item">
                      <span className="time">{formatTs(event.at)}</span>
                      <span className={`level ${event.level.toLowerCase()}`}>{event.level}</span>
                      <span className="action">{event.action}</span>
                      <span className="detail">{event.detail}</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>
        </>
      )}
    </section>
  );
}
