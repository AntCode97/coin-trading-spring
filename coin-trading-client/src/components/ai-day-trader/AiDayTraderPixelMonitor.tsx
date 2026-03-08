import { useEffect, useMemo, useRef, useState } from 'react';
import type { GuidedClosedTradeView } from '../../api';
import type {
  AiMonitorActor,
  AiTraderEvent,
  AiTraderState,
} from '../../lib/ai-day-trader/AiDayTraderModel';
import {
  formatClock,
  formatDateTime,
  formatExitReason,
  formatKrw,
  formatPercent,
} from './AiDayTraderScreenParts';
import './AiDayTraderPixelMonitor.css';

type RoomId = 'scanner' | 'ranking' | 'entry' | 'manage' | 'execution' | 'subagent' | 'market';

interface MonitorRoom {
  id: RoomId;
  title: string;
  subtitle: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

interface MonitorLayoutState {
  zoom: number;
  panX: number;
  panY: number;
  rooms: Record<RoomId, MonitorRoom>;
}

type MonitorSelection =
  | { kind: 'actor'; actorId: string }
  | { kind: 'market'; market: string }
  | { kind: 'position'; tradeId: number }
  | { kind: 'room'; roomId: RoomId }
  | null;

interface PositionedActor {
  actor: AiMonitorActor;
  x: number;
  y: number;
}

interface AiDayTraderPixelMonitorProps {
  open: boolean;
  state: AiTraderState;
  todayTrades: GuidedClosedTradeView[];
  onClose: () => void;
  onFocusActor: (actorId: string | null) => void;
}

const LAYOUT_KEY = 'ai-scalp-pixel-monitor.layout.v1';
const WORLD_WIDTH = 1680;
const WORLD_HEIGHT = 1040;
const GRID_SIZE = 16;

const DEFAULT_ROOMS: Record<RoomId, MonitorRoom> = {
  scanner: { id: 'scanner', title: 'Scanner Room', subtitle: '유동성 스캔', x: 72, y: 78, width: 240, height: 170 },
  ranking: { id: 'ranking', title: 'Ranking Desk', subtitle: '숏리스트 정렬', x: 360, y: 78, width: 240, height: 170 },
  entry: { id: 'entry', title: 'Entry Lab', subtitle: '진입 판단', x: 648, y: 78, width: 260, height: 190 },
  manage: { id: 'manage', title: 'Position Control', subtitle: '보유 관리', x: 950, y: 78, width: 280, height: 260 },
  execution: { id: 'execution', title: 'Execution Booth', subtitle: '주문 체결', x: 1270, y: 78, width: 250, height: 180 },
  subagent: { id: 'subagent', title: 'Subagent Bay', subtitle: 'delegate / tool', x: 980, y: 382, width: 540, height: 220 },
  market: { id: 'market', title: 'Market Board', subtitle: '후보 / 포지션 / 오늘 거래', x: 72, y: 314, width: 856, height: 430 },
};

const DEFAULT_LAYOUT: MonitorLayoutState = {
  zoom: 0.82,
  panX: 36,
  panY: 28,
  rooms: DEFAULT_ROOMS,
};

const ROOM_BY_ROLE: Record<AiMonitorActor['role'], RoomId> = {
  SCAN: 'scanner',
  RANK: 'ranking',
  ENTRY: 'entry',
  MANAGE: 'manage',
  EXECUTION: 'execution',
  DELEGATE: 'subagent',
};

const ROOM_DESCRIPTIONS: Record<RoomId, string> = {
  scanner: '시장 스캔 루프가 도는 공간이다. 유니버스 조회, 후보 필터링, 다음 스캔 대기를 본다.',
  ranking: 'LLM이 상위 후보를 압축하는 구간이다. shortlist 실패/폴백도 여기서 보인다.',
  entry: '후보별 진입 컨텍스트 조회와 BUY/WAIT 판단이 발생하는 구간이다.',
  manage: '보유 포지션 재평가, 정체 청산, 시간 청산, 손실 방어가 모이는 공간이다.',
  execution: '시장가 매수/매도와 체결 성공, 주문 실패를 보는 공간이다.',
  subagent: 'delegate_to_zai_agent 또는 실제 tool call이 발생할 때만 서브 에이전트가 잠깐 생성된다.',
  market: '현재 큐, 보유 포지션, 오늘 거래 내역을 묶어서 보는 보드다.',
};

function snap(value: number): number {
  return Math.round(value / GRID_SIZE) * GRID_SIZE;
}

function clamp(value: number, minValue: number, maxValue: number): number {
  return Math.min(maxValue, Math.max(minValue, value));
}

function loadLayout(): MonitorLayoutState {
  try {
    const raw = window.localStorage.getItem(LAYOUT_KEY);
    if (!raw) return DEFAULT_LAYOUT;
    const parsed = JSON.parse(raw) as Partial<MonitorLayoutState>;
    return {
      zoom: clamp(typeof parsed.zoom === 'number' ? parsed.zoom : DEFAULT_LAYOUT.zoom, 0.6, 1.4),
      panX: typeof parsed.panX === 'number' ? parsed.panX : DEFAULT_LAYOUT.panX,
      panY: typeof parsed.panY === 'number' ? parsed.panY : DEFAULT_LAYOUT.panY,
      rooms: {
        scanner: { ...DEFAULT_ROOMS.scanner, ...(parsed.rooms?.scanner ?? {}) },
        ranking: { ...DEFAULT_ROOMS.ranking, ...(parsed.rooms?.ranking ?? {}) },
        entry: { ...DEFAULT_ROOMS.entry, ...(parsed.rooms?.entry ?? {}) },
        manage: { ...DEFAULT_ROOMS.manage, ...(parsed.rooms?.manage ?? {}) },
        execution: { ...DEFAULT_ROOMS.execution, ...(parsed.rooms?.execution ?? {}) },
        subagent: { ...DEFAULT_ROOMS.subagent, ...(parsed.rooms?.subagent ?? {}) },
        market: { ...DEFAULT_ROOMS.market, ...(parsed.rooms?.market ?? {}) },
      },
    };
  } catch {
    return DEFAULT_LAYOUT;
  }
}

function saveLayout(layout: MonitorLayoutState) {
  window.localStorage.setItem(LAYOUT_KEY, JSON.stringify(layout));
}

function roomCenter(room: MonitorRoom): { x: number; y: number } {
  return {
    x: room.x + room.width / 2,
    y: room.y + room.height / 2,
  };
}

function coreActorOffset(role: AiMonitorActor['role']): { x: number; y: number } {
  switch (role) {
    case 'SCAN':
      return { x: 0, y: 14 };
    case 'RANK':
      return { x: 0, y: 8 };
    case 'ENTRY':
      return { x: -8, y: 10 };
    case 'MANAGE':
      return { x: -52, y: -34 };
    case 'EXECUTION':
      return { x: 8, y: 14 };
    case 'DELEGATE':
    default:
      return { x: 0, y: 0 };
  }
}

function buildPositionedActors(actors: AiMonitorActor[], rooms: Record<RoomId, MonitorRoom>): PositionedActor[] {
  const subagents = actors.filter((actor) => actor.kind === 'SUBAGENT');
  return actors.map((actor) => {
    const room = rooms[ROOM_BY_ROLE[actor.role]];
    const center = roomCenter(room);
    if (actor.kind === 'CORE') {
      const offset = coreActorOffset(actor.role);
      return {
        actor,
        x: center.x + offset.x,
        y: center.y + offset.y,
      };
    }
    const index = subagents.findIndex((item) => item.id === actor.id);
    const col = index % 4;
    const row = Math.floor(index / 4);
    return {
      actor,
      x: room.x + 72 + col * 98,
      y: room.y + 78 + row * 52,
    };
  });
}

function getRelatedEvents(events: AiTraderEvent[], selection: MonitorSelection, actor?: AiMonitorActor): AiTraderEvent[] {
  if (!selection) return events.slice(0, 6);
  if (selection.kind === 'market' || selection.kind === 'position') {
    const market = selection.kind === 'market' ? selection.market : undefined;
    return events.filter((event) => event.market === market).slice(0, 6);
  }
  if (selection.kind === 'actor' && actor) {
    const byRole = events.filter((event) => {
      switch (actor.role) {
        case 'SCAN':
          return event.type === 'SCAN';
        case 'RANK':
          return event.type === 'RANK';
        case 'ENTRY':
          return event.type === 'BUY_DECISION';
        case 'MANAGE':
          return event.type === 'MANAGE_DECISION';
        case 'EXECUTION':
          return event.type === 'BUY_EXECUTION' || event.type === 'SELL_EXECUTION' || event.type === 'ERROR';
        case 'DELEGATE':
          return event.market === actor.market;
        default:
          return false;
      }
    });
    return byRole.slice(0, 6);
  }
  if (selection.kind === 'room') {
    return events.slice(0, 6);
  }
  return events.slice(0, 6);
}

function getRelatedTrades(trades: GuidedClosedTradeView[], selection: MonitorSelection, actor?: AiMonitorActor): GuidedClosedTradeView[] {
  if (!selection) return trades.slice(0, 6);
  if (selection.kind === 'market') {
    return trades.filter((trade) => trade.market === selection.market).slice(0, 6);
  }
  if (selection.kind === 'position') {
    return trades.slice(0, 6);
  }
  if (selection.kind === 'actor' && actor?.market) {
    return trades.filter((trade) => trade.market === actor.market).slice(0, 6);
  }
  return trades.slice(0, 6);
}

export default function AiDayTraderPixelMonitor({
  open,
  state,
  todayTrades,
  onClose,
  onFocusActor,
}: AiDayTraderPixelMonitorProps) {
  const viewportRef = useRef<HTMLDivElement | null>(null);
  const [layout, setLayout] = useState<MonitorLayoutState>(() => loadLayout());
  const [editMode, setEditMode] = useState(false);
  const [selection, setSelection] = useState<MonitorSelection>(null);
  const dragRef = useRef<
    | null
    | { kind: 'pan'; startX: number; startY: number; panX: number; panY: number }
    | { kind: 'room'; roomId: RoomId; startX: number; startY: number; originX: number; originY: number }
  >(null);

  const positionedActors = useMemo(
    () => buildPositionedActors(state.monitorActors, layout.rooms),
    [layout.rooms, state.monitorActors],
  );

  const selectedActor = useMemo(
    () => (selection?.kind === 'actor' ? state.monitorActors.find((actor) => actor.id === selection.actorId) ?? null : null),
    [selection, state.monitorActors],
  );
  const relatedEvents = useMemo(
    () => getRelatedEvents(state.events, selection, selectedActor ?? undefined),
    [selectedActor, selection, state.events],
  );
  const relatedTrades = useMemo(
    () => getRelatedTrades(todayTrades, selection, selectedActor ?? undefined),
    [selectedActor, selection, todayTrades],
  );
  const selectedPosition = useMemo(
    () => (selection?.kind === 'position'
      ? state.positions.find((position) => position.tradeId === selection.tradeId) ?? null
      : null),
    [selection, state.positions],
  );

  useEffect(() => {
    if (!open) return;
    const focusedActor = state.monitorFocusId
      ? state.monitorActors.find((actor) => actor.id === state.monitorFocusId)
      : null;
    const fallbackActor = focusedActor
      ?? state.monitorActors.find((actor) => actor.status === 'BUSY')
      ?? state.monitorActors[0]
      ?? null;
    if (!selection && fallbackActor) {
      setSelection({ kind: 'actor', actorId: fallbackActor.id });
      onFocusActor(fallbackActor.id);
    }
  }, [onFocusActor, open, selection, state.monitorActors, state.monitorFocusId]);

  useEffect(() => {
    if (!open || !state.monitorFocusId) return;
    const focusedActor = state.monitorActors.find((actor) => actor.id === state.monitorFocusId);
    if (!focusedActor) return;
    if (selection?.kind === 'actor' && selection.actorId === focusedActor.id) return;
    if (focusedActor.kind === 'SUBAGENT' || focusedActor.status === 'BUSY') {
      setSelection({ kind: 'actor', actorId: focusedActor.id });
    }
  }, [open, selection, state.monitorActors, state.monitorFocusId]);

  useEffect(() => {
    if (!open) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [onClose, open]);

  useEffect(() => {
    if (!selection || selection.kind !== 'actor') return;
    if (state.monitorActors.some((actor) => actor.id === selection.actorId)) return;
    const fallbackActor = state.monitorActors.find((actor) => actor.id === state.monitorFocusId) ?? state.monitorActors[0];
    if (fallbackActor) {
      setSelection({ kind: 'actor', actorId: fallbackActor.id });
      onFocusActor(fallbackActor.id);
    } else {
      setSelection(null);
      onFocusActor(null);
    }
  }, [onFocusActor, selection, state.monitorActors, state.monitorFocusId]);

  if (!open) {
    return null;
  }

  const centerOnPoint = (worldX: number, worldY: number) => {
    const viewport = viewportRef.current;
    if (!viewport) return;
    const rect = viewport.getBoundingClientRect();
    setLayout((current) => ({
      ...current,
      panX: rect.width / 2 - worldX * current.zoom,
      panY: rect.height / 2 - worldY * current.zoom,
    }));
  };

  const centerOnSelection = (nextSelection: MonitorSelection) => {
    if (!nextSelection) return;
    if (nextSelection.kind === 'room') {
      const room = layout.rooms[nextSelection.roomId];
      const center = roomCenter(room);
      centerOnPoint(center.x, center.y);
      return;
    }
    if (nextSelection.kind === 'actor') {
      const actor = positionedActors.find((item) => item.actor.id === nextSelection.actorId);
      if (actor) {
        centerOnPoint(actor.x, actor.y);
      }
      return;
    }
    if (nextSelection.kind === 'market') {
      const room = layout.rooms.market;
      centerOnPoint(room.x + room.width * 0.35, room.y + room.height * 0.56);
      return;
    }
    if (nextSelection.kind === 'position') {
      const room = layout.rooms.manage;
      centerOnPoint(room.x + room.width * 0.55, room.y + room.height * 0.7);
    }
  };

  const handleSelect = (nextSelection: MonitorSelection) => {
    setSelection(nextSelection);
    if (nextSelection?.kind === 'actor') {
      onFocusActor(nextSelection.actorId);
    } else {
      onFocusActor(null);
    }
    centerOnSelection(nextSelection);
  };

  const changeZoom = (delta: number) => {
    setLayout((current) => ({
      ...current,
      zoom: clamp(Number((current.zoom + delta).toFixed(2)), 0.6, 1.4),
    }));
  };

  const handleViewportPointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement;
    if (
      target.closest('[data-room-drag-handle="true"]') ||
      target.closest('.ai-pixel-room') ||
      target.closest('.ai-pixel-actor')
    ) {
      return;
    }
    dragRef.current = {
      kind: 'pan',
      startX: event.clientX,
      startY: event.clientY,
      panX: layout.panX,
      panY: layout.panY,
    };
  };

  const handleRoomPointerDown = (roomId: RoomId, event: React.PointerEvent<HTMLDivElement>) => {
    if (!editMode) return;
    event.stopPropagation();
    dragRef.current = {
      kind: 'room',
      roomId,
      startX: event.clientX,
      startY: event.clientY,
      originX: layout.rooms[roomId].x,
      originY: layout.rooms[roomId].y,
    };
  };

  const handlePointerMove = (event: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (!drag) return;
    if (drag.kind === 'pan') {
      setLayout((current) => ({
        ...current,
        panX: drag.panX + (event.clientX - drag.startX),
        panY: drag.panY + (event.clientY - drag.startY),
      }));
      return;
    }
    const nextX = snap(drag.originX + (event.clientX - drag.startX) / layout.zoom);
    const nextY = snap(drag.originY + (event.clientY - drag.startY) / layout.zoom);
    setLayout((current) => ({
      ...current,
      rooms: {
        ...current.rooms,
        [drag.roomId]: {
          ...current.rooms[drag.roomId],
          x: nextX,
          y: nextY,
        },
      },
    }));
  };

  const endDrag = () => {
    dragRef.current = null;
  };

  const activeQueueMarkets = state.queue.slice(0, 6);
  const activePositions = state.positions.slice(0, 6);

  return (
    <div className="ai-pixel-monitor" onClick={onClose}>
      <div
        className="ai-pixel-monitor__dialog"
        onClick={(event) => event.stopPropagation()}
      >
        <div
          className="ai-pixel-monitor__viewport"
          ref={viewportRef}
          onPointerDown={handleViewportPointerDown}
          onPointerMove={handlePointerMove}
          onPointerUp={endDrag}
          onPointerLeave={endDrag}
        >
          <div
            className="ai-pixel-monitor__world"
            style={{
              width: WORLD_WIDTH,
              height: WORLD_HEIGHT,
              transform: `translate(${layout.panX}px, ${layout.panY}px) scale(${layout.zoom})`,
              transformOrigin: 'top left',
            }}
          >
            {Object.values(layout.rooms).map((room) => (
              <div
                key={room.id}
                className={`ai-pixel-room ${selection?.kind === 'room' && selection.roomId === room.id ? 'is-selected' : ''}`}
                style={{
                  left: room.x,
                  top: room.y,
                  width: room.width,
                  height: room.height,
                }}
                onClick={() => handleSelect({ kind: 'room', roomId: room.id })}
              >
                <div
                  className={`ai-pixel-room__header ${editMode ? 'is-editable' : ''}`}
                  onPointerDown={(event) => handleRoomPointerDown(room.id, event)}
                  data-room-drag-handle="true"
                >
                  <div className="ai-pixel-room__title">{room.title}</div>
                  <div className="ai-pixel-room__subtitle">{room.subtitle}</div>
                </div>

                {room.id === 'market' && (
                  <div className="ai-pixel-room__board">
                    <div className="ai-pixel-room__section">
                      <span>Queue</span>
                      <div className="ai-pixel-room__chips">
                        {activeQueueMarkets.length === 0 ? (
                          <span className="ai-pixel-room__hint">후보 없음</span>
                        ) : (
                          activeQueueMarkets.map((market) => (
                            <button
                              key={market.market}
                              type="button"
                              className={`ai-pixel-chip ${selection?.kind === 'market' && selection.market === market.market ? 'is-selected' : ''}`}
                              onClick={(event) => {
                                event.stopPropagation();
                                handleSelect({ kind: 'market', market: market.market });
                              }}
                            >
                              {market.market.replace('KRW-', '')}
                            </button>
                          ))
                        )}
                      </div>
                    </div>
                    <div className="ai-pixel-room__section">
                      <span>Today</span>
                      <div className="ai-pixel-room__ticker">
                        {todayTrades.slice(0, 5).map((trade) => (
                          <button
                            key={trade.tradeId}
                            type="button"
                            className="ai-pixel-trade"
                            onClick={(event) => {
                              event.stopPropagation();
                              handleSelect({ kind: 'market', market: trade.market });
                            }}
                          >
                            <strong>{trade.market.replace('KRW-', '')}</strong>
                            <span className={trade.realizedPnl >= 0 ? 'positive' : 'negative'}>
                              {formatPercent(trade.realizedPnlPercent)}
                            </span>
                          </button>
                        ))}
                      </div>
                    </div>
                  </div>
                )}

                {room.id === 'manage' && (
                  <div className="ai-pixel-room__board ai-pixel-room__board--positions">
                    <div className="ai-pixel-room__section">
                      <span>Positions</span>
                      <div className="ai-pixel-room__ticker">
                        {activePositions.length === 0 ? (
                          <span className="ai-pixel-room__hint">보유 없음</span>
                        ) : (
                          activePositions.map((position) => (
                            <button
                              key={position.tradeId}
                              type="button"
                              className={`ai-pixel-position-tile ${selection?.kind === 'position' && selection.tradeId === position.tradeId ? 'is-selected' : ''}`}
                              onClick={(event) => {
                                event.stopPropagation();
                                handleSelect({ kind: 'position', tradeId: position.tradeId });
                              }}
                            >
                              <strong>{position.market.replace('KRW-', '')}</strong>
                              <span className={position.unrealizedPnlPercent >= 0 ? 'positive' : 'negative'}>
                                {formatPercent(position.unrealizedPnlPercent)}
                              </span>
                            </button>
                          ))
                        )}
                      </div>
                    </div>
                  </div>
                )}
              </div>
            ))}

            {positionedActors.map(({ actor, x, y }) => (
              <button
                key={actor.id}
                type="button"
                className={`ai-pixel-actor ai-pixel-actor--${actor.status.toLowerCase()} ${selection?.kind === 'actor' && selection.actorId === actor.id ? 'is-selected' : ''}`}
                style={{ left: x, top: y }}
                onClick={(event) => {
                  event.stopPropagation();
                  handleSelect({ kind: 'actor', actorId: actor.id });
                }}
                title={`${actor.label} · ${actor.status}`}
              >
                <span className={`ai-pixel-actor__sprite ai-pixel-actor__sprite--${actor.kind.toLowerCase()}`} />
                <span className="ai-pixel-actor__label">{actor.market ? `${actor.label} · ${actor.market.replace('KRW-', '')}` : actor.label}</span>
              </button>
            ))}
          </div>
        </div>

        <aside className="ai-pixel-monitor__detail">
          {selection?.kind === 'actor' && selectedActor ? (
            <>
              <div className="ai-pixel-detail__section">
                <div className="ai-pixel-detail__eyebrow">{selectedActor.kind === 'CORE' ? 'Core Agent' : 'Subagent'}</div>
                <div className="ai-pixel-detail__title">{selectedActor.label}</div>
                <div className="ai-pixel-detail__chips">
                  <span>{selectedActor.role}</span>
                  <span>{selectedActor.status}</span>
                  {selectedActor.market && <span>{selectedActor.market}</span>}
                </div>
                {selectedActor.taskSummary && (
                  <p className="ai-pixel-detail__body">{selectedActor.taskSummary}</p>
                )}
                {selectedActor.lastResult && (
                  <div className="ai-pixel-detail__result">{selectedActor.lastResult}</div>
                )}
              </div>
              <div className="ai-pixel-detail__section">
                <div className="ai-pixel-detail__subtitle">관련 저널</div>
                <div className="ai-pixel-detail__list">
                  {relatedEvents.map((event) => (
                    <article key={event.id} className="ai-pixel-detail__item">
                      <div className="ai-pixel-detail__item-title">{event.message}</div>
                      <div className="ai-pixel-detail__item-meta">{event.type} · {formatClock(event.timestamp)}</div>
                    </article>
                  ))}
                </div>
              </div>
              <div className="ai-pixel-detail__section">
                <div className="ai-pixel-detail__subtitle">관련 거래</div>
                <div className="ai-pixel-detail__list">
                  {relatedTrades.length === 0 ? (
                    <div className="ai-pixel-detail__empty">관련 거래 없음</div>
                  ) : (
                    relatedTrades.map((trade) => (
                      <article key={trade.tradeId} className="ai-pixel-detail__item">
                        <div className="ai-pixel-detail__item-title">
                          {trade.market.replace('KRW-', '')} · {formatKrw(trade.realizedPnl)}
                        </div>
                        <div className="ai-pixel-detail__item-meta">
                          {formatDateTime(trade.createdAt)} → {trade.closedAt ? formatDateTime(trade.closedAt) : '-'} · {formatExitReason(trade.exitReason)}
                        </div>
                      </article>
                    ))
                  )}
                </div>
              </div>
            </>
          ) : selection?.kind === 'market' ? (
            <>
              <div className="ai-pixel-detail__section">
                <div className="ai-pixel-detail__eyebrow">Market Focus</div>
                <div className="ai-pixel-detail__title">{selection.market}</div>
                <div className="ai-pixel-detail__chips">
                  <span>Queue / Trade Filter</span>
                </div>
              </div>
              <div className="ai-pixel-detail__section">
                <div className="ai-pixel-detail__subtitle">관련 저널</div>
                <div className="ai-pixel-detail__list">
                  {relatedEvents.map((event) => (
                    <article key={event.id} className="ai-pixel-detail__item">
                      <div className="ai-pixel-detail__item-title">{event.message}</div>
                      <div className="ai-pixel-detail__item-meta">{event.type} · {formatClock(event.timestamp)}</div>
                    </article>
                  ))}
                </div>
              </div>
              <div className="ai-pixel-detail__section">
                <div className="ai-pixel-detail__subtitle">오늘 거래</div>
                <div className="ai-pixel-detail__list">
                  {relatedTrades.length === 0 ? (
                    <div className="ai-pixel-detail__empty">오늘 거래 없음</div>
                  ) : (
                    relatedTrades.map((trade) => (
                      <article key={trade.tradeId} className="ai-pixel-detail__item">
                        <div className="ai-pixel-detail__item-title">
                          {formatKrw(trade.realizedPnl)} / {formatPercent(trade.realizedPnlPercent)}
                        </div>
                        <div className="ai-pixel-detail__item-meta">
                          {formatDateTime(trade.createdAt)} → {trade.closedAt ? formatDateTime(trade.closedAt) : '-'} · {formatExitReason(trade.exitReason)}
                        </div>
                      </article>
                    ))
                  )}
                </div>
              </div>
            </>
          ) : selection?.kind === 'position' && selectedPosition ? (
            <>
              <div className="ai-pixel-detail__section">
                <div className="ai-pixel-detail__eyebrow">Open Position</div>
                <div className="ai-pixel-detail__title">{selectedPosition.market}</div>
                <div className="ai-pixel-detail__chips">
                  <span>{formatPercent(selectedPosition.unrealizedPnlPercent)}</span>
                  <span>entry {selectedPosition.averageEntryPrice.toLocaleString()}</span>
                  <span>now {selectedPosition.currentPrice.toLocaleString()}</span>
                </div>
              </div>
              <div className="ai-pixel-detail__section">
                <div className="ai-pixel-detail__subtitle">보호 가격</div>
                <div className="ai-pixel-detail__body">
                  손절 {selectedPosition.stopLossPrice.toLocaleString()} · 익절 {selectedPosition.takeProfitPrice.toLocaleString()}
                </div>
              </div>
              <div className="ai-pixel-detail__section">
                <div className="ai-pixel-detail__subtitle">최근 이벤트</div>
                <div className="ai-pixel-detail__list">
                  {state.events
                    .filter((event) => event.market === selectedPosition.market)
                    .slice(0, 6)
                    .map((event) => (
                      <article key={event.id} className="ai-pixel-detail__item">
                        <div className="ai-pixel-detail__item-title">{event.message}</div>
                        <div className="ai-pixel-detail__item-meta">{event.type} · {formatClock(event.timestamp)}</div>
                      </article>
                    ))}
                </div>
              </div>
            </>
          ) : selection?.kind === 'room' ? (
            <>
              <div className="ai-pixel-detail__section">
                <div className="ai-pixel-detail__eyebrow">Room</div>
                <div className="ai-pixel-detail__title">{layout.rooms[selection.roomId].title}</div>
                <p className="ai-pixel-detail__body">{ROOM_DESCRIPTIONS[selection.roomId]}</p>
              </div>
              <div className="ai-pixel-detail__section">
                <div className="ai-pixel-detail__subtitle">좌표</div>
                <div className="ai-pixel-detail__chips">
                  <span>x {layout.rooms[selection.roomId].x}</span>
                  <span>y {layout.rooms[selection.roomId].y}</span>
                  <span>{layout.rooms[selection.roomId].width}×{layout.rooms[selection.roomId].height}</span>
                </div>
              </div>
            </>
          ) : (
            <div className="ai-pixel-detail__empty">
              에이전트나 시장을 클릭하면 여기서 상세 내용을 볼 수 있습니다.
            </div>
          )}
        </aside>

        <footer className="ai-pixel-monitor__toolbar">
          <div className="ai-pixel-toolbar__group">
            <button type="button" className="ai-pixel-toolbar__button" onClick={() => setEditMode((current) => !current)}>
              {editMode ? '편집 종료' : '편집 모드'}
            </button>
            <button type="button" className="ai-pixel-toolbar__button" onClick={() => saveLayout(layout)}>
              저장
            </button>
            <button type="button" className="ai-pixel-toolbar__button" onClick={() => setLayout(loadLayout())}>
              리셋
            </button>
            <button type="button" className="ai-pixel-toolbar__button" onClick={() => setLayout(DEFAULT_LAYOUT)}>
              기본 배치 복원
            </button>
          </div>
          <div className="ai-pixel-toolbar__group">
            <button type="button" className="ai-pixel-toolbar__button" onClick={() => changeZoom(-0.08)}>
              줌 -
            </button>
            <button type="button" className="ai-pixel-toolbar__button" onClick={() => changeZoom(0.08)}>
              줌 +
            </button>
            <button
              type="button"
              className="ai-pixel-toolbar__button"
              onClick={() => centerOnSelection(selection)}
              disabled={!selection}
            >
              포커스
            </button>
            <button type="button" className="ai-pixel-toolbar__button danger" onClick={onClose}>
              닫기
            </button>
          </div>
        </footer>
      </div>
    </div>
  );
}
