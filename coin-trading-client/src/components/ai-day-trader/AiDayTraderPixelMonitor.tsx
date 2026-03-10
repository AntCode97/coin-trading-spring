import { type CSSProperties, type ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import type { GuidedClosedTradeView } from '../../api';
import type {
  AiDayTraderConfig,
  AiMonitorActor,
  AiTraderEvent,
  AiTraderState,
} from '../../lib/ai-day-trader/AiDayTraderModel';
import { requestOneShotTextWithMeta } from '../../lib/llmService';
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
  spriteVariant: number;
}

interface RoomSummary {
  status: AiMonitorActor['status'];
  statusLabel: string;
  headline: string;
  detail: string;
  counter?: string;
}

interface QuickFocusItem {
  roomId: RoomId;
  label: string;
  detail: string;
  selection: MonitorSelection;
  statusLabel?: string;
}

interface WorkflowLink {
  id: string;
  from: RoomId;
  to: RoomId;
  path: string;
  tone: 'idle' | 'live' | 'success' | 'alert';
  duration: number;
  packetCount: number;
}

interface MarketPulseItem {
  id: string;
  tone: 'neutral' | 'positive' | 'negative' | 'active';
  label: string;
}

interface AiDayTraderPixelMonitorProps {
  open: boolean;
  state: AiTraderState;
  todayTrades: GuidedClosedTradeView[];
  config: AiDayTraderConfig;
  providerConnected: boolean;
  onClose: () => void;
  onFocusActor: (actorId: string | null) => void;
}

const LAYOUT_KEY = 'ai-scalp-pixel-monitor.layout.v2';
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
    return sanitizeLayout({
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
    });
  } catch {
    return DEFAULT_LAYOUT;
  }
}

function saveLayout(layout: MonitorLayoutState) {
  window.localStorage.setItem(LAYOUT_KEY, JSON.stringify(sanitizeLayout(layout)));
}

function sanitizeLayout(layout: MonitorLayoutState): MonitorLayoutState {
  return {
    ...layout,
    zoom: clamp(layout.zoom, 0.6, 1.4),
    rooms: {
      scanner: sanitizeRoom(layout.rooms.scanner, DEFAULT_ROOMS.scanner),
      ranking: sanitizeRoom(layout.rooms.ranking, DEFAULT_ROOMS.ranking),
      entry: sanitizeRoom(layout.rooms.entry, DEFAULT_ROOMS.entry),
      manage: sanitizeRoom(layout.rooms.manage, DEFAULT_ROOMS.manage),
      execution: sanitizeRoom(layout.rooms.execution, DEFAULT_ROOMS.execution),
      subagent: sanitizeRoom(layout.rooms.subagent, DEFAULT_ROOMS.subagent),
      market: sanitizeRoom(layout.rooms.market, DEFAULT_ROOMS.market),
    },
  };
}

function sanitizeRoom(room: MonitorRoom | undefined, fallback: MonitorRoom): MonitorRoom {
  const width = clamp(room?.width ?? fallback.width, 200, WORLD_WIDTH);
  const height = clamp(room?.height ?? fallback.height, 140, WORLD_HEIGHT);
  return {
    ...fallback,
    ...room,
    width,
    height,
    x: clamp(snap(room?.x ?? fallback.x), 0, WORLD_WIDTH - width),
    y: clamp(snap(room?.y ?? fallback.y), 0, WORLD_HEIGHT - height),
  };
}

function getRoomsBounds(rooms: Record<RoomId, MonitorRoom>) {
  const entries = Object.values(rooms);
  const minX = Math.min(...entries.map((room) => room.x));
  const minY = Math.min(...entries.map((room) => room.y));
  const maxX = Math.max(...entries.map((room) => room.x + room.width));
  const maxY = Math.max(...entries.map((room) => room.y + room.height));
  return {
    minX,
    minY,
    maxX,
    maxY,
    width: maxX - minX,
    height: maxY - minY,
  };
}

function fitLayoutToViewport(layout: MonitorLayoutState, viewportWidth: number, viewportHeight: number): MonitorLayoutState {
  const bounds = getRoomsBounds(layout.rooms);
  const horizontalPadding = 40;
  const topPadding = 118;
  const bottomPadding = 42;
  const availableWidth = Math.max(viewportWidth - horizontalPadding * 2, 320);
  const availableHeight = Math.max(viewportHeight - topPadding - bottomPadding, 240);
  const nextZoom = clamp(
    Math.min(availableWidth / bounds.width, availableHeight / bounds.height, 1.05),
    0.64,
    1.18,
  );
  const panX = horizontalPadding + (availableWidth - bounds.width * nextZoom) / 2 - bounds.minX * nextZoom;
  const panY = topPadding + (availableHeight - bounds.height * nextZoom) / 2 - bounds.minY * nextZoom;
  return {
    ...layout,
    zoom: Number(nextZoom.toFixed(2)),
    panX: Math.round(panX),
    panY: Math.round(panY),
  };
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
    const spriteVariant = getActorSpriteVariant(actor, subagents.findIndex((item) => item.id === actor.id));
    if (actor.kind === 'CORE') {
      const offset = coreActorOffset(actor.role);
      return {
        actor,
        x: center.x + offset.x,
        y: center.y + offset.y,
        spriteVariant,
      };
    }
    const index = subagents.findIndex((item) => item.id === actor.id);
    const col = index % 4;
    const row = Math.floor(index / 4);
    return {
      actor,
      x: room.x + 72 + col * 98,
      y: room.y + 78 + row * 52,
      spriteVariant,
    };
  });
}

function getActorSpriteVariant(actor: AiMonitorActor, subagentIndex = 0): number {
  if (actor.kind === 'SUBAGENT') {
    return Math.abs(subagentIndex) % 5;
  }
  switch (actor.role) {
    case 'SCAN':
      return 2;
    case 'RANK':
      return 4;
    case 'ENTRY':
      return 0;
    case 'MANAGE':
      return 3;
    case 'EXECUTION':
      return 1;
    case 'DELEGATE':
    default:
      return 2;
  }
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
          return event.type === 'RANK' || event.type === 'REVIEW';
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

function getRoomEvents(roomId: RoomId, events: AiTraderEvent[]): AiTraderEvent[] {
  switch (roomId) {
    case 'scanner':
      return events.filter((event) => event.type === 'SCAN').slice(0, 6);
    case 'ranking':
      return events.filter((event) => event.type === 'RANK' || event.type === 'REVIEW').slice(0, 6);
    case 'entry':
      return events.filter((event) => event.type === 'BUY_DECISION').slice(0, 6);
    case 'manage':
      return events.filter((event) => event.type === 'MANAGE_DECISION' || event.type === 'SELL_EXECUTION').slice(0, 6);
    case 'execution':
      return events.filter((event) =>
        event.type === 'BUY_EXECUTION' || event.type === 'SELL_EXECUTION' || event.type === 'ERROR').slice(0, 6);
    case 'subagent':
      return events.filter((event) => event.type === 'ERROR' || event.type === 'RANK' || event.type === 'REVIEW' || event.type === 'BUY_DECISION').slice(0, 6);
    case 'market':
      return events.slice(0, 6);
    default:
      return events.slice(0, 6);
  }
}

function getRoomTrades(roomId: RoomId, state: AiTraderState, trades: GuidedClosedTradeView[]): GuidedClosedTradeView[] {
  switch (roomId) {
    case 'manage':
    case 'execution':
    case 'market':
      return trades.slice(0, 6);
    case 'entry': {
      const markets = new Set(state.queue.slice(0, 4).map((item) => item.market));
      return trades.filter((trade) => markets.has(trade.market)).slice(0, 6);
    }
    default:
      return [];
  }
}

function summarizeRoom(roomId: RoomId, state: AiTraderState): RoomSummary | null {
  const coreActor = state.monitorActors.find((actor) => actor.kind === 'CORE' && ROOM_BY_ROLE[actor.role] === roomId);
  const latestExecutionEvent = state.events.find((event) =>
    event.type === 'BUY_EXECUTION' || event.type === 'SELL_EXECUTION' || event.type === 'ERROR');
  const latestManageEvent = state.events.find((event) => event.type === 'MANAGE_DECISION');
  const activeSubagents = state.monitorActors.filter((actor) => actor.kind === 'SUBAGENT');
  const latestSubagent = activeSubagents[0] ?? null;
  const statusLabel = coreActor
    ? {
        IDLE: 'IDLE',
        BUSY: 'LIVE',
        SUCCESS: 'CLEAR',
        ERROR: 'ALERT',
        WAITING: 'WAIT',
      }[coreActor.status]
    : 'N/A';

  switch (roomId) {
    case 'scanner':
      return {
        status: coreActor?.status ?? 'IDLE',
        statusLabel,
        headline: coreActor?.taskSummary ?? '유동성 상위 시장을 순환 스캔합니다.',
        detail: `최근 스캔 ${state.lastScanAt ? formatClock(state.lastScanAt) : '-'} · 사이클 ${state.scanCycles}회`,
        counter: `${state.queue.length} queued`,
      };
    case 'ranking':
      return {
        status: coreActor?.status ?? 'IDLE',
        statusLabel,
        headline: coreActor?.taskSummary ?? 'LLM shortlist 압축 대기',
        detail: state.queue.length > 0
          ? `${state.queue[0].market.replace('KRW-', '')} 외 ${Math.max(state.queue.length - 1, 0)}개 후보 정렬`
          : '큐가 비어 있어 다음 스캔을 기다립니다.',
        counter: `${Math.min(state.queue.length, 12)} shortlist`,
      };
    case 'entry':
      return {
        status: coreActor?.status ?? 'IDLE',
        statusLabel,
        headline: coreActor?.taskSummary ?? '진입 후보를 아직 검토하지 않았습니다.',
        detail: `최종 진입 검토 ${state.finalistsReviewed}건 · 매수 실행 ${state.buyExecutions}건`,
        counter: `${Math.min(state.queue.length, 4)} finalists`,
      };
    case 'manage':
      return {
        status: coreActor?.status ?? 'IDLE',
        statusLabel,
        headline: coreActor?.taskSummary ?? '보유 포지션 모니터링 중',
        detail: latestManageEvent?.message ?? '정체 손실, 시간 청산, 이익 반납 방지를 감시합니다.',
        counter: `${state.positions.length} open`,
      };
    case 'execution':
      return {
        status: coreActor?.status ?? 'IDLE',
        statusLabel,
        headline: coreActor?.taskSummary ?? '주문 실행 대기',
        detail: latestExecutionEvent?.message ?? '최근 주문 실행 이벤트가 없습니다.',
        counter: `${state.buyExecutions} fills`,
      };
    case 'subagent':
      return {
        status: latestSubagent?.status ?? 'IDLE',
        statusLabel: latestSubagent
          ? {
              IDLE: 'IDLE',
              BUSY: 'LIVE',
              SUCCESS: 'CLEAR',
              ERROR: 'ALERT',
              WAITING: 'WAIT',
            }[latestSubagent.status]
          : 'EMPTY',
        headline: latestSubagent?.taskSummary ?? 'delegate / tool 호출이 생기면 여기에 나타납니다.',
        detail: latestSubagent?.lastResult ?? '현재 활성 서브 에이전트가 없습니다.',
        counter: `${activeSubagents.length} active`,
      };
    case 'market':
      return {
        status: 'BUSY',
        statusLabel: 'BOARD',
        headline: state.queue.length > 0
          ? `${state.queue[0].market.replace('KRW-', '')}가 현재 최우선 후보입니다.`
          : '현재 후보 큐가 비어 있습니다.',
        detail: `보유 ${state.positions.length}개 · 오늘 거래 ${todayTradesCountFromState(state)}건`,
        counter: `${state.queue.length} queue`,
      };
    default:
      return null;
  }
}

function todayTradesCountFromState(state: AiTraderState): number {
  return state.closedTrades.length;
}

function focusSelectionForRoom(roomId: RoomId, actors: AiMonitorActor[]): MonitorSelection {
  if (roomId === 'market') {
    return { kind: 'room', roomId };
  }
  if (roomId === 'subagent') {
    const subagent = actors.find((actor) => actor.kind === 'SUBAGENT');
    return subagent ? { kind: 'actor', actorId: subagent.id } : { kind: 'room', roomId };
  }
  const coreActor = actors.find((actor) => actor.kind === 'CORE' && ROOM_BY_ROLE[actor.role] === roomId);
  return coreActor ? { kind: 'actor', actorId: coreActor.id } : { kind: 'room', roomId };
}

function isSameSelection(left: MonitorSelection, right: MonitorSelection): boolean {
  if (!left || !right || left.kind !== right.kind) {
    return false;
  }
  switch (left.kind) {
    case 'actor':
      return left.actorId === (right as Extract<MonitorSelection, { kind: 'actor' }>).actorId;
    case 'room':
      return left.roomId === (right as Extract<MonitorSelection, { kind: 'room' }>).roomId;
    case 'market':
      return left.market === (right as Extract<MonitorSelection, { kind: 'market' }>).market;
    case 'position':
      return left.tradeId === (right as Extract<MonitorSelection, { kind: 'position' }>).tradeId;
    default:
      return true;
  }
}

function renderRoomDecor(roomId: RoomId, summary: RoomSummary | null): ReactNode {
  switch (roomId) {
    case 'scanner':
      return (
        <div className="ai-pixel-room__decor ai-pixel-room__decor--scanner" aria-hidden="true">
          <div className="ai-pixel-furniture ai-pixel-furniture--bookshelf left" />
          <div className="ai-pixel-furniture ai-pixel-furniture--bookshelf right" />
          <div className="ai-pixel-furniture ai-pixel-furniture--plant corner" />
          <div className="ai-pixel-furniture ai-pixel-furniture--desk wide" />
          <div className="ai-pixel-furniture ai-pixel-furniture--chair center" />
          <div className="ai-pixel-room__speech">
            <span className="ai-pixel-room__speech-tag">{summary?.statusLabel ?? 'SCAN'}</span>
            <span className="ai-pixel-room__speech-text">{summary?.headline ?? '시장 순환 스캔'}</span>
          </div>
        </div>
      );
    case 'ranking':
      return (
        <div className="ai-pixel-room__decor ai-pixel-room__decor--ranking" aria-hidden="true">
          <div className="ai-pixel-furniture ai-pixel-furniture--bookshelf left" />
          <div className="ai-pixel-furniture ai-pixel-furniture--bookshelf right" />
          <div className="ai-pixel-furniture ai-pixel-furniture--desk dual" />
          <div className="ai-pixel-furniture ai-pixel-furniture--monitor-cluster" />
          <div className="ai-pixel-room__speech">
            <span className="ai-pixel-room__speech-tag">{summary?.counter ?? 'shortlist'}</span>
            <span className="ai-pixel-room__speech-text">{summary?.headline ?? '후보 압축'}</span>
          </div>
        </div>
      );
    case 'entry':
      return (
        <div className="ai-pixel-room__decor ai-pixel-room__decor--entry" aria-hidden="true">
          <div className="ai-pixel-furniture ai-pixel-furniture--counter" />
          <div className="ai-pixel-furniture ai-pixel-furniture--desk analyst" />
          <div className="ai-pixel-furniture ai-pixel-furniture--lamp" />
          <div className="ai-pixel-room__speech">
            <span className="ai-pixel-room__speech-tag">{summary?.statusLabel ?? 'ENTRY'}</span>
            <span className="ai-pixel-room__speech-text">{summary?.headline ?? '진입 검토 중'}</span>
          </div>
        </div>
      );
    case 'manage':
      return (
        <div className="ai-pixel-room__decor ai-pixel-room__decor--manage" aria-hidden="true">
          <div className="ai-pixel-furniture ai-pixel-furniture--painting" />
          <div className="ai-pixel-furniture ai-pixel-furniture--plant left" />
          <div className="ai-pixel-furniture ai-pixel-furniture--plant right" />
          <div className="ai-pixel-furniture ai-pixel-furniture--couch left" />
          <div className="ai-pixel-furniture ai-pixel-furniture--couch right" />
          <div className="ai-pixel-furniture ai-pixel-furniture--table coffee" />
          <div className="ai-pixel-room__speech lounge">
            <span className="ai-pixel-room__speech-tag">{summary?.counter ?? 'open'}</span>
            <span className="ai-pixel-room__speech-text">{summary?.headline ?? '포지션 모니터링'}</span>
          </div>
        </div>
      );
    case 'execution':
      return (
        <div className="ai-pixel-room__decor ai-pixel-room__decor--execution" aria-hidden="true">
          <div className="ai-pixel-furniture ai-pixel-furniture--fridge" />
          <div className="ai-pixel-furniture ai-pixel-furniture--watercooler" />
          <div className="ai-pixel-furniture ai-pixel-furniture--counter pantry" />
          <div className="ai-pixel-furniture ai-pixel-furniture--trash" />
          <div className="ai-pixel-room__speech compact">
            <span className="ai-pixel-room__speech-tag">{summary?.statusLabel ?? 'EXEC'}</span>
            <span className="ai-pixel-room__speech-text">{summary?.headline ?? '주문 대기'}</span>
          </div>
        </div>
      );
    case 'subagent':
      return (
        <div className="ai-pixel-room__decor ai-pixel-room__decor--subagent" aria-hidden="true">
          <div className="ai-pixel-furniture ai-pixel-furniture--server left" />
          <div className="ai-pixel-furniture ai-pixel-furniture--server center" />
          <div className="ai-pixel-furniture ai-pixel-furniture--server right" />
          <div className="ai-pixel-room__speech compact">
            <span className="ai-pixel-room__speech-tag">{summary?.statusLabel ?? 'EMPTY'}</span>
            <span className="ai-pixel-room__speech-text">{summary?.headline ?? 'delegate 대기'}</span>
          </div>
        </div>
      );
    case 'market':
      return (
        <div className="ai-pixel-room__decor ai-pixel-room__decor--market" aria-hidden="true">
          <div className="ai-pixel-furniture ai-pixel-furniture--board-wall" />
          <div className="ai-pixel-furniture ai-pixel-furniture--ticker-strip" />
          <div className="ai-pixel-room__speech board">
            <span className="ai-pixel-room__speech-tag">{summary?.counter ?? 'queue'}</span>
            <span className="ai-pixel-room__speech-text">{summary?.headline ?? '후보 모니터'}</span>
          </div>
        </div>
      );
    default:
      return null;
  }
}

function statusTone(status: AiMonitorActor['status'] | undefined): WorkflowLink['tone'] {
  switch (status) {
    case 'BUSY':
      return 'live';
    case 'SUCCESS':
      return 'success';
    case 'ERROR':
      return 'alert';
    case 'WAITING':
    case 'IDLE':
    default:
      return 'idle';
  }
}

function createWorkflowPath(from: MonitorRoom, to: MonitorRoom): string {
  const start = roomCenter(from);
  const end = roomCenter(to);
  const midX = start.x + (end.x - start.x) / 2;
  return `M ${start.x} ${start.y} C ${midX} ${start.y}, ${midX} ${end.y}, ${end.x} ${end.y}`;
}

function buildWorkflowLinks(
  rooms: Record<RoomId, MonitorRoom>,
  state: AiTraderState,
): WorkflowLink[] {
  const coreByRole = new Map(
    state.monitorActors
      .filter((actor) => actor.kind === 'CORE')
      .map((actor) => [actor.role, actor] as const),
  );
  const subagents = state.monitorActors.filter((actor) => actor.kind === 'SUBAGENT');
  const latestEvent = state.events[0];
  const hasExecutionError = latestEvent?.type === 'ERROR';

  return [
    {
      id: 'scan-rank',
      from: 'scanner',
      to: 'ranking',
      path: createWorkflowPath(rooms.scanner, rooms.ranking),
      tone: coreByRole.get('SCAN')?.status === 'BUSY' || coreByRole.get('RANK')?.status === 'BUSY'
        ? 'live'
        : statusTone(coreByRole.get('RANK')?.status),
      duration: 3.8,
      packetCount: state.queue.length > 0 ? 2 : 1,
    },
    {
      id: 'rank-entry',
      from: 'ranking',
      to: 'entry',
      path: createWorkflowPath(rooms.ranking, rooms.entry),
      tone: coreByRole.get('ENTRY')?.status === 'BUSY' || state.queue.length > 0
        ? 'live'
        : statusTone(coreByRole.get('ENTRY')?.status),
      duration: 3.2,
      packetCount: Math.max(1, Math.min(state.queue.length, 3)),
    },
    {
      id: 'entry-manage',
      from: 'entry',
      to: 'manage',
      path: createWorkflowPath(rooms.entry, rooms.manage),
      tone: state.positions.length > 0 || coreByRole.get('MANAGE')?.status === 'BUSY'
        ? 'live'
        : statusTone(coreByRole.get('MANAGE')?.status),
      duration: 4.4,
      packetCount: Math.max(1, Math.min(state.positions.length, 3)),
    },
    {
      id: 'entry-execution',
      from: 'entry',
      to: 'execution',
      path: createWorkflowPath(rooms.entry, rooms.execution),
      tone: hasExecutionError
        ? 'alert'
        : coreByRole.get('EXECUTION')?.status === 'BUSY' || latestEvent?.type === 'BUY_EXECUTION'
          ? 'live'
          : statusTone(coreByRole.get('EXECUTION')?.status),
      duration: 2.8,
      packetCount: Math.max(1, Math.min(state.buyExecutions, 3)),
    },
    {
      id: 'manage-execution',
      from: 'manage',
      to: 'execution',
      path: createWorkflowPath(rooms.manage, rooms.execution),
      tone: hasExecutionError
        ? 'alert'
        : latestEvent?.type === 'SELL_EXECUTION'
          ? 'success'
          : statusTone(coreByRole.get('MANAGE')?.status),
      duration: 3.4,
      packetCount: state.positions.length > 0 ? 2 : 1,
    },
    {
      id: 'rank-subagent',
      from: 'ranking',
      to: 'subagent',
      path: createWorkflowPath(rooms.ranking, rooms.subagent),
      tone: subagents.length > 0 || latestEvent?.type === 'REVIEW' ? 'live' : 'idle',
      duration: 4.8,
      packetCount: Math.max(1, Math.min(subagents.length, 3)),
    },
    {
      id: 'entry-subagent',
      from: 'entry',
      to: 'subagent',
      path: createWorkflowPath(rooms.entry, rooms.subagent),
      tone: subagents.some((actor) => actor.market) ? 'live' : 'idle',
      duration: 4.2,
      packetCount: Math.max(1, Math.min(subagents.length, 2)),
    },
  ];
}

function buildMarketPulseItems(state: AiTraderState, todayTrades: GuidedClosedTradeView[]): MarketPulseItem[] {
  const queueItems: MarketPulseItem[] = state.queue.slice(0, 4).map((item) => ({
    id: `queue-${item.market}`,
    tone: item.score >= 88 ? 'active' : 'neutral',
    label: `${item.market.replace('KRW-', '')} score ${item.score}`,
  }));
  const positionItems: MarketPulseItem[] = state.positions.slice(0, 3).map((position) => ({
    id: `position-${position.tradeId}`,
    tone: position.unrealizedPnlPercent >= 0 ? 'positive' : 'negative',
    label: `${position.market.replace('KRW-', '')} hold ${formatPercent(position.unrealizedPnlPercent)}`,
  }));
  const tradeItems: MarketPulseItem[] = todayTrades.slice(0, 4).map((trade) => ({
    id: `trade-${trade.tradeId}`,
    tone: trade.realizedPnl >= 0 ? 'positive' : 'negative',
    label: `${trade.market.replace('KRW-', '')} ${formatPercent(trade.realizedPnlPercent)}`,
  }));
  const items = [...queueItems, ...positionItems, ...tradeItems];
  return items.length > 0
    ? items
    : [{ id: 'pulse-empty', tone: 'neutral', label: '다음 스캔을 기다리는 중' }];
}

function buildLiveMomentItems(events: AiTraderEvent[]): MarketPulseItem[] {
  const items: MarketPulseItem[] = events.slice(0, 4).map((event) => ({
    id: event.id,
    tone: event.type === 'ERROR' ? 'negative' : event.type === 'SELL_EXECUTION' ? 'positive' : 'active',
    label: `${event.type} · ${event.market?.replace('KRW-', '') ?? 'SYSTEM'} · ${formatClock(event.timestamp)}`,
  }));
  return items.length > 0
    ? items
    : [{ id: 'moment-empty', tone: 'neutral', label: '아직 실시간 이벤트가 없습니다.' }];
}

function actorEmote(actor: AiMonitorActor): string | null {
  if (actor.status === 'ERROR') return '!!';
  if (actor.status === 'SUCCESS') return 'OK';
  if (actor.status === 'WAITING') return '...';
  if (actor.status !== 'BUSY') return null;
  switch (actor.role) {
    case 'SCAN':
      return 'PING';
    case 'RANK':
      return 'SORT';
    case 'ENTRY':
      return 'BUY?';
    case 'MANAGE':
      return 'HOLD';
    case 'EXECUTION':
      return 'FILL';
    case 'DELEGATE':
      return 'TOOL';
    default:
      return 'LIVE';
  }
}

function actorMotionStyle(actor: AiMonitorActor, x: number, y: number, index: number): CSSProperties {
  const seed = [...actor.id].reduce((sum, char) => sum + char.charCodeAt(0), 0) + index;
  const delay = `${-(seed % 7) * 0.21}s`;
  const duration = `${1.65 + (seed % 4) * 0.24}s`;
  const style: CSSProperties = {
    left: x,
    top: y,
  };
  (style as Record<string, string | number>)['--actor-delay'] = delay;
  (style as Record<string, string | number>)['--actor-duration'] = duration;
  return style;
}

function buildActorStatusBrief(
  actor: AiMonitorActor,
  relatedEvents: AiTraderEvent[],
  relatedTrades: GuidedClosedTradeView[],
  state: AiTraderState,
): string {
  const latestEvent = relatedEvents[0];
  const latestTrade = relatedTrades[0];
  const eventPart = latestEvent ? `최근엔 ${latestEvent.type}로 ${latestEvent.message}` : '아직 최신 이벤트는 없습니다';
  const tradePart = latestTrade
    ? `가장 가까운 거래는 ${latestTrade.market.replace('KRW-', '')} ${formatExitReason(latestTrade.exitReason)}입니다`
    : '연결된 거래는 아직 없습니다';
  const marketPart = actor.market ? `${actor.market} 기준으로 보고 있고, ` : '';

  switch (actor.role) {
    case 'SCAN':
      return `${marketPart}유동성 상위 ${state.queue.length}개 후보를 훑고 있습니다. ${eventPart}.`;
    case 'RANK':
      return `${marketPart}큐 ${state.queue.length}개를 추려 shortlist 우선순위를 매기고 있습니다. ${eventPart}.`;
    case 'ENTRY':
      return `${marketPart}최종 진입 검토 ${state.finalistsReviewed}건 중 무엇을 살지 거르고 있습니다. ${eventPart}.`;
    case 'MANAGE':
      return `${marketPart}현재 보유 ${state.positions.length}개를 감시하면서 시간 청산과 손실 방어를 보고 있습니다. ${eventPart}.`;
    case 'EXECUTION':
      return `${marketPart}주문 실행 ${state.buyExecutions}건 기준으로 체결과 실패를 감시합니다. ${eventPart}.`;
    case 'DELEGATE':
      return `${marketPart}${actor.taskSummary ?? '서브 태스크'}를 처리 중입니다. ${tradePart}.`;
    default:
      return actor.taskSummary ?? eventPart ?? tradePart;
  }
}

function buildFallbackActorAnswer(
  question: string,
  actor: AiMonitorActor,
  relatedEvents: AiTraderEvent[],
  relatedTrades: GuidedClosedTradeView[],
  state: AiTraderState,
): string {
  const normalized = question.trim();
  const latestEvent = relatedEvents[0];
  const latestTrade = relatedTrades[0];
  const statusBrief = buildActorStatusBrief(actor, relatedEvents, relatedTrades, state);

  if (!normalized || normalized.includes('뭐') || normalized.includes('무슨') || normalized.includes('하고')) {
    return statusBrief;
  }
  if (normalized.includes('왜')) {
    return actor.taskSummary
      ? `${actor.taskSummary} 때문에 여기서 이 역할을 맡고 있습니다. ${latestEvent ? `지금 기준 근거는 ${latestEvent.message}입니다.` : '아직 추가 근거는 없습니다.'}`
      : `${actor.role} 역할상 지금 필요한 판단을 처리하려고 여기 있습니다. ${latestEvent ? `최근 근거는 ${latestEvent.message}입니다.` : ''}`;
  }
  if (normalized.includes('다음') || normalized.includes('이후') || normalized.includes('뭘 할')) {
    switch (actor.role) {
      case 'SCAN':
        return `다음 스캔에서 다시 후보를 채우거나, 큐가 있으면 Ranker로 넘깁니다.`;
      case 'RANK':
        return `다음엔 shortlist를 Entry로 넘겨서 실제 BUY/WAIT 판단을 받게 됩니다.`;
      case 'ENTRY':
        return `다음엔 BUY로 실행하거나 WAIT로 버립니다. ${latestEvent ? `현재 근거는 ${latestEvent.message}입니다.` : ''}`;
      case 'MANAGE':
        return `다음엔 HOLD를 유지하거나, 손실 방어/시간 청산/이익 반납 방지 조건이면 SELL 쪽으로 넘깁니다.`;
      case 'EXECUTION':
        return `다음엔 주문 체결 성공을 기록하거나 실패를 남기고 다시 제어 루프로 돌려보냅니다.`;
      case 'DELEGATE':
        return `이 서브 태스크가 끝나면 부모 에이전트로 결과를 돌려보냅니다.`;
      default:
        return statusBrief;
    }
  }
  if (normalized.includes('위험') || normalized.includes('리스크')) {
    return latestTrade
      ? `최근 연결 거래 기준으론 ${latestTrade.market.replace('KRW-', '')}에서 ${formatPercent(latestTrade.realizedPnlPercent)} 결과가 있었습니다. 지금은 ${state.positions.length}개 포지션과 최신 이벤트를 기준으로 보수적으로 움직여야 합니다.`
      : `가장 큰 리스크는 근거 약한 진입과 체결 후 반전입니다. 지금은 최신 이벤트와 포지션 수 ${state.positions.length}개를 보며 방어적으로 판단합니다.`;
  }
  return `${statusBrief} 질문 기준으로 보면 ${latestEvent ? `최근 핵심 이벤트는 ${latestEvent.message}` : '추가 이벤트는 아직 적고'} ${latestTrade ? `가장 가까운 거래는 ${latestTrade.market.replace('KRW-', '')} ${formatExitReason(latestTrade.exitReason)}입니다.` : '거래 기록은 아직 없습니다.'}`;
}

export default function AiDayTraderPixelMonitor({
  open,
  state,
  todayTrades,
  config,
  providerConnected,
  onClose,
  onFocusActor,
}: AiDayTraderPixelMonitorProps) {
  const viewportRef = useRef<HTMLDivElement | null>(null);
  const [layout, setLayout] = useState<MonitorLayoutState>(() => loadLayout());
  const [editMode, setEditMode] = useState(false);
  const [selection, setSelection] = useState<MonitorSelection>(null);
  const [agentQuestion, setAgentQuestion] = useState('');
  const [agentAnswer, setAgentAnswer] = useState<{ question: string; answer: string; source: 'SYSTEM' | 'LLM' } | null>(null);
  const [agentAnswerBusy, setAgentAnswerBusy] = useState(false);
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
  const roomSummaries = useMemo<Record<RoomId, RoomSummary | null>>(
    () => ({
      scanner: summarizeRoom('scanner', state),
      ranking: summarizeRoom('ranking', state),
      entry: summarizeRoom('entry', state),
      manage: summarizeRoom('manage', state),
      execution: summarizeRoom('execution', state),
      subagent: summarizeRoom('subagent', state),
      market: summarizeRoom('market', state),
    }),
    [state],
  );
  const selectedRoomSummary = useMemo(
    () => (selection?.kind === 'room' ? roomSummaries[selection.roomId] : null),
    [roomSummaries, selection],
  );
  const selectedRoomEvents = useMemo(
    () => (selection?.kind === 'room' ? getRoomEvents(selection.roomId, state.events) : []),
    [selection, state.events],
  );
  const selectedRoomTrades = useMemo(
    () => (selection?.kind === 'room' ? getRoomTrades(selection.roomId, state, todayTrades) : []),
    [selection, state, todayTrades],
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
    if (!selectedActor) {
      setAgentAnswer(null);
      setAgentQuestion('');
      setAgentAnswerBusy(false);
      return;
    }
    setAgentQuestion('');
    setAgentAnswer({
      question: '지금 뭐하고 있어?',
      answer: buildActorStatusBrief(selectedActor, relatedEvents, relatedTrades, state),
      source: 'SYSTEM',
    });
  }, [relatedEvents, relatedTrades, selectedActor, state]);

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

  useEffect(() => {
    if (!open) return;
    const viewport = viewportRef.current;
    if (!viewport) return;
    setLayout((current) => fitLayoutToViewport(current, viewport.clientWidth, viewport.clientHeight));
  }, [open]);

  const quickFocusItems = useMemo<QuickFocusItem[]>(
    () => ([
      {
        roomId: 'scanner',
        label: 'Scanner',
        detail: roomSummaries.scanner?.counter ?? 'scan',
        selection: focusSelectionForRoom('scanner', state.monitorActors),
        statusLabel: roomSummaries.scanner?.statusLabel,
      },
      {
        roomId: 'ranking',
        label: 'Ranking',
        detail: roomSummaries.ranking?.counter ?? 'rank',
        selection: focusSelectionForRoom('ranking', state.monitorActors),
        statusLabel: roomSummaries.ranking?.statusLabel,
      },
      {
        roomId: 'entry',
        label: 'Entry',
        detail: roomSummaries.entry?.counter ?? 'entry',
        selection: focusSelectionForRoom('entry', state.monitorActors),
        statusLabel: roomSummaries.entry?.statusLabel,
      },
      {
        roomId: 'manage',
        label: 'Manage',
        detail: roomSummaries.manage?.counter ?? 'manage',
        selection: focusSelectionForRoom('manage', state.monitorActors),
        statusLabel: roomSummaries.manage?.statusLabel,
      },
      {
        roomId: 'execution',
        label: 'Execution',
        detail: roomSummaries.execution?.counter ?? 'exec',
        selection: focusSelectionForRoom('execution', state.monitorActors),
        statusLabel: roomSummaries.execution?.statusLabel,
      },
      {
        roomId: 'market',
        label: 'Market Board',
        detail: `${state.queue.length} queue`,
        selection: focusSelectionForRoom('market', state.monitorActors),
        statusLabel: roomSummaries.market?.statusLabel,
      },
    ]),
    [roomSummaries, state.monitorActors, state.queue.length],
  );
  const workflowLinks = useMemo(
    () => buildWorkflowLinks(layout.rooms, state),
    [layout.rooms, state],
  );
  const marketPulseItems = useMemo(
    () => buildMarketPulseItems(state, todayTrades),
    [state, todayTrades],
  );
  const liveMomentItems = useMemo(
    () => buildLiveMomentItems(state.events),
    [state.events],
  );

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

  const askSelectedActor = async (rawQuestion: string) => {
    if (!selectedActor) return;
    const question = rawQuestion.trim();
    if (!question) return;
    const fallbackAnswer = buildFallbackActorAnswer(question, selectedActor, relatedEvents, relatedTrades, state);
    if (!providerConnected) {
      setAgentAnswer({ question, answer: fallbackAnswer, source: 'SYSTEM' });
      return;
    }

    setAgentAnswerBusy(true);
    try {
      const prompt = [
        `너는 데스크톱 AI 초단타 모니터 안의 ${selectedActor.label} 에이전트다.`,
        `역할: ${selectedActor.role}`,
        `상태: ${selectedActor.status}`,
        selectedActor.market ? `시장: ${selectedActor.market}` : '',
        selectedActor.taskSummary ? `현재 작업: ${selectedActor.taskSummary}` : '',
        selectedActor.lastResult ? `최근 결과: ${selectedActor.lastResult}` : '',
        `오픈 포지션 수: ${state.positions.length}`,
        `대기 후보 수: ${state.queue.length}`,
        `최근 이벤트: ${relatedEvents.slice(0, 3).map((event) => `${event.type} ${event.message}`).join(' | ') || '없음'}`,
        `최근 거래: ${relatedTrades.slice(0, 2).map((trade) => `${trade.market} ${formatPercent(trade.realizedPnlPercent)} ${formatExitReason(trade.exitReason)}`).join(' | ') || '없음'}`,
        '규칙: 한국어로 2~4문장, 과장 금지, 지금 화면에 있는 사실만 근거로 답하라. 모르면 모른다고 말하라. 투자 권유 말투보다 현재 작업 브리핑 말투를 써라.',
        `사용자 질문: ${question}`,
      ].filter(Boolean).join('\n');

      const response = await requestOneShotTextWithMeta({
        provider: config.provider,
        model: config.model,
        prompt,
      });
      setAgentAnswer({
        question,
        answer: response.text.trim() || fallbackAnswer,
        source: 'LLM',
      });
    } catch {
      setAgentAnswer({
        question,
        answer: fallbackAnswer,
        source: 'SYSTEM',
      });
    } finally {
      setAgentAnswerBusy(false);
    }
  };

  const changeZoom = (delta: number) => {
    setLayout((current) => ({
      ...current,
      zoom: clamp(Number((current.zoom + delta).toFixed(2)), 0.6, 1.4),
    }));
  };

  const fitWorldToViewport = () => {
    const viewport = viewportRef.current;
    if (!viewport) return;
    setLayout((current) => fitLayoutToViewport(current, viewport.clientWidth, viewport.clientHeight));
  };

  const resetToSavedLayout = () => {
    const viewport = viewportRef.current;
    const saved = loadLayout();
    if (!viewport) {
      setLayout(saved);
      return;
    }
    setLayout(fitLayoutToViewport(saved, viewport.clientWidth, viewport.clientHeight));
  };

  const restoreDefaultLayout = () => {
    const viewport = viewportRef.current;
    if (!viewport) {
      setLayout(DEFAULT_LAYOUT);
      return;
    }
    setLayout(fitLayoutToViewport(DEFAULT_LAYOUT, viewport.clientWidth, viewport.clientHeight));
  };

  const handleViewportPointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement;
    if (
      target.closest('[data-room-drag-handle="true"]') ||
      target.closest('.ai-pixel-actor') ||
      target.closest('button')
    ) {
      return;
    }
    if (editMode && target.closest('.ai-pixel-room')) {
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
    const room = layout.rooms[drag.roomId];
    const nextX = clamp(
      snap(drag.originX + (event.clientX - drag.startX) / layout.zoom),
      0,
      WORLD_WIDTH - room.width,
    );
    const nextY = clamp(
      snap(drag.originY + (event.clientY - drag.startY) / layout.zoom),
      0,
      WORLD_HEIGHT - room.height,
    );
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

  const handleViewportWheel = (event: React.WheelEvent<HTMLDivElement>) => {
    event.preventDefault();
    const delta = event.deltaY > 0 ? -0.06 : 0.06;
    setLayout((current) => ({
      ...current,
      zoom: clamp(Number((current.zoom + delta).toFixed(2)), 0.6, 1.4),
    }));
  };

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
          onWheel={handleViewportWheel}
          onDoubleClick={fitWorldToViewport}
        >
          <div className="ai-pixel-monitor__hud">
            <div className="ai-pixel-monitor__hud-copy">
              <span className="ai-pixel-monitor__hud-eyebrow">AI Agent Monitor</span>
              <strong className="ai-pixel-monitor__hud-title">{editMode ? '편집 모드' : '탐색 모드'}</strong>
              <span className="ai-pixel-monitor__hud-hint">
                {editMode
                  ? '방 헤더를 드래그해 재배치하고 저장하면 다음에도 그대로 유지됩니다.'
                  : '캐릭터, 큐, 포지션을 클릭해 상세를 보고 빈 공간 드래그로 이동하세요.'}
              </span>
              <div className="ai-pixel-monitor__hud-feed">
                <div className="ai-pixel-monitor__hud-feed-track">
                  {[...liveMomentItems, ...liveMomentItems].map((item, index) => (
                    <span
                      key={`${item.id}-${index}`}
                      className={`ai-pixel-monitor__hud-feed-item is-${item.tone}`}
                    >
                      {item.label}
                    </span>
                  ))}
                </div>
              </div>
            </div>
            <div className="ai-pixel-monitor__hud-stats">
              <span>활성 에이전트 {state.monitorActors.filter((actor) => actor.status === 'BUSY').length}</span>
              <span>보유 {state.positions.length}</span>
              <span>오늘 거래 {todayTrades.length}</span>
              <span>줌 {(layout.zoom * 100).toFixed(0)}%</span>
            </div>
          </div>
          <div
            className="ai-pixel-monitor__world"
            style={{
              width: WORLD_WIDTH,
              height: WORLD_HEIGHT,
              transform: `translate(${layout.panX}px, ${layout.panY}px) scale(${layout.zoom})`,
              transformOrigin: 'top left',
            }}
          >
            <svg
              className="ai-pixel-monitor__network"
              viewBox={`0 0 ${WORLD_WIDTH} ${WORLD_HEIGHT}`}
              aria-hidden="true"
            >
              {workflowLinks.map((link) => (
                <g
                  key={link.id}
                  className={`ai-pixel-monitor__flow is-${link.tone}`}
                >
                  <path className="ai-pixel-monitor__flow-base" d={link.path} />
                  <path className="ai-pixel-monitor__flow-glow" d={link.path} />
                  {Array.from({ length: link.packetCount }).map((_, packetIndex) => (
                    <circle
                      key={`${link.id}-${packetIndex}`}
                      className="ai-pixel-monitor__flow-packet"
                      r="4"
                      cx="0"
                      cy="0"
                    >
                      <animateMotion
                        dur={`${link.duration}s`}
                        begin={`${packetIndex * 0.58}s`}
                        repeatCount="indefinite"
                        path={link.path}
                      />
                    </circle>
                  ))}
                </g>
              ))}
            </svg>
            {Object.values(layout.rooms).map((room) => (
              <div
                key={room.id}
                className={`ai-pixel-room ai-pixel-room--${room.id} ${selection?.kind === 'room' && selection.roomId === room.id ? 'is-selected' : ''}`}
                data-status={roomSummaries[room.id]?.status.toLowerCase() ?? 'idle'}
                style={{
                  left: room.x,
                  top: room.y,
                  width: room.width,
                  height: room.height,
                }}
                onClick={() => {
                  if (!editMode) return;
                  handleSelect({ kind: 'room', roomId: room.id });
                }}
                >
                  <div
                    className={`ai-pixel-room__header ${editMode ? 'is-editable' : ''}`}
                    onPointerDown={(event) => handleRoomPointerDown(room.id, event)}
                    onClick={(event) => {
                      event.stopPropagation();
                      if (!editMode) return;
                      handleSelect({ kind: 'room', roomId: room.id });
                    }}
                    data-room-drag-handle="true"
                  >
                    <div className="ai-pixel-room__title">{room.title}</div>
                    <div className="ai-pixel-room__subtitle">{room.subtitle}</div>
                  </div>

                  {renderRoomDecor(room.id, roomSummaries[room.id])}
                  <div className={`ai-pixel-room__signal ai-pixel-room__signal--${roomSummaries[room.id]?.status.toLowerCase() ?? 'idle'}`}>
                    <span />
                    <span />
                    <span />
                    <span />
                  </div>

                {room.id === 'market' && (
                  <div className="ai-pixel-room__board">
                    <div className="ai-pixel-room__feed">
                      <div className="ai-pixel-room__feed-track">
                        {[...marketPulseItems, ...marketPulseItems].map((item, index) => (
                          <span key={`${item.id}-${index}`} className={`ai-pixel-room__feed-item is-${item.tone}`}>
                            {item.label}
                          </span>
                        ))}
                      </div>
                    </div>
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

            {positionedActors.map(({ actor, x, y, spriteVariant }, index) => (
              <button
                key={actor.id}
                type="button"
                className={`ai-pixel-actor ai-pixel-actor--${actor.role.toLowerCase()} ai-pixel-actor--${actor.status.toLowerCase()} ${selection?.kind === 'actor' && selection.actorId === actor.id ? 'is-selected' : ''}`}
                style={actorMotionStyle(actor, x, y, index)}
                onClick={(event) => {
                  event.stopPropagation();
                  handleSelect({ kind: 'actor', actorId: actor.id });
                }}
                title={`${actor.label} · ${actor.status}`}
              >
                <span className="ai-pixel-actor__shadow" />
                <span className={`ai-pixel-actor__sprite ai-pixel-actor__sprite--${actor.role.toLowerCase()} ai-pixel-actor__sprite--variant-${spriteVariant}`} />
                {actorEmote(actor) && (
                  <span className={`ai-pixel-actor__emote ai-pixel-actor__emote--${actor.status.toLowerCase()}`}>
                    {actorEmote(actor)}
                  </span>
                )}
                <span className="ai-pixel-actor__label">{actor.market ? `${actor.label} · ${actor.market.replace('KRW-', '')}` : actor.label}</span>
              </button>
            ))}
          </div>
        </div>

        <aside className="ai-pixel-monitor__detail">
          <div className="ai-pixel-detail__section ai-pixel-detail__section--primary">
            <div className="ai-pixel-detail__eyebrow">빠른 이동</div>
            <div className="ai-pixel-detail__title">지금 바로 볼 곳</div>
            <div className="ai-pixel-detail__nav">
              {quickFocusItems.map((item) => {
                const isSelected = isSameSelection(selection, item.selection);
                return (
                  <button
                    key={item.roomId}
                    type="button"
                    className={`ai-pixel-detail__nav-button ai-pixel-detail__nav-button--${item.roomId} ${isSelected ? 'is-selected' : ''}`}
                    onClick={() => handleSelect(item.selection)}
                  >
                    <strong>{item.label}</strong>
                    <span>{item.detail}</span>
                    {item.statusLabel && <em>{item.statusLabel}</em>}
                  </button>
                );
              })}
            </div>
          </div>

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
                <div className="ai-pixel-detail__subtitle">에이전트 브리핑</div>
                {agentAnswer && (
                  <div className="ai-pixel-detail__reply">
                    <div className="ai-pixel-detail__reply-meta">
                      <span>{agentAnswer.source === 'LLM' ? 'LLM 답변' : '즉답 모드'}</span>
                      <strong>{agentAnswer.question}</strong>
                    </div>
                    <div className="ai-pixel-detail__reply-body">{agentAnswer.answer}</div>
                  </div>
                )}
                <div className="ai-pixel-detail__quick-asks">
                  {['지금 뭐하고 있어?', '왜 이걸 보고 있어?', '다음엔 뭘 할 거야?'].map((question) => (
                    <button
                      key={question}
                      type="button"
                      className="ai-pixel-detail__ask-chip"
                      onClick={() => void askSelectedActor(question)}
                      disabled={agentAnswerBusy}
                    >
                      {question}
                    </button>
                  ))}
                </div>
                <div className="ai-pixel-detail__ask-row">
                  <input
                    className="ai-pixel-detail__ask-input"
                    value={agentQuestion}
                    onChange={(event) => setAgentQuestion(event.target.value)}
                    placeholder="에이전트에게 질문하기"
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') {
                        event.preventDefault();
                        void askSelectedActor(agentQuestion);
                      }
                    }}
                  />
                  <button
                    type="button"
                    className="ai-pixel-detail__ask-button"
                    onClick={() => void askSelectedActor(agentQuestion)}
                    disabled={agentAnswerBusy || agentQuestion.trim().length === 0}
                  >
                    {agentAnswerBusy ? '답변 중' : '질문'}
                  </button>
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
                <div className="ai-pixel-detail__chips">
                  {selectedRoomSummary?.statusLabel && <span>{selectedRoomSummary.statusLabel}</span>}
                  {selectedRoomSummary?.counter && <span>{selectedRoomSummary.counter}</span>}
                </div>
                {selectedRoomSummary?.headline && (
                  <p className="ai-pixel-detail__body">{selectedRoomSummary.headline}</p>
                )}
                {selectedRoomSummary?.detail && (
                  <div className="ai-pixel-detail__result">{selectedRoomSummary.detail}</div>
                )}
                <p className="ai-pixel-detail__body">{ROOM_DESCRIPTIONS[selection.roomId]}</p>
              </div>
              <div className="ai-pixel-detail__section">
                <div className="ai-pixel-detail__subtitle">관련 저널</div>
                <div className="ai-pixel-detail__list">
                  {selectedRoomEvents.length === 0 ? (
                    <div className="ai-pixel-detail__empty">관련 이벤트 없음</div>
                  ) : (
                    selectedRoomEvents.map((event) => (
                      <article key={event.id} className="ai-pixel-detail__item">
                        <div className="ai-pixel-detail__item-title">{event.message}</div>
                        <div className="ai-pixel-detail__item-meta">{event.type} · {formatClock(event.timestamp)}</div>
                      </article>
                    ))
                  )}
                </div>
              </div>
              {selectedRoomTrades.length > 0 && (
                <div className="ai-pixel-detail__section">
                  <div className="ai-pixel-detail__subtitle">관련 거래</div>
                  <div className="ai-pixel-detail__list">
                    {selectedRoomTrades.map((trade) => (
                      <article key={trade.tradeId} className="ai-pixel-detail__item">
                        <div className="ai-pixel-detail__item-title">
                          {trade.market.replace('KRW-', '')} · {formatKrw(trade.realizedPnl)}
                        </div>
                        <div className="ai-pixel-detail__item-meta">
                          {trade.closedAt ? formatDateTime(trade.closedAt) : '-'} · {formatExitReason(trade.exitReason)}
                        </div>
                      </article>
                    ))}
                  </div>
                </div>
              )}
              {editMode && (
                <div className="ai-pixel-detail__section">
                  <div className="ai-pixel-detail__subtitle">좌표</div>
                  <div className="ai-pixel-detail__chips">
                    <span>x {layout.rooms[selection.roomId].x}</span>
                    <span>y {layout.rooms[selection.roomId].y}</span>
                    <span>{layout.rooms[selection.roomId].width}×{layout.rooms[selection.roomId].height}</span>
                  </div>
                </div>
              )}
            </>
          ) : (
            <div className="ai-pixel-detail__empty">
              에이전트나 시장을 클릭하면 여기서 상세 내용을 볼 수 있습니다.
            </div>
          )}
        </aside>

        <footer className="ai-pixel-monitor__toolbar">
          <div className="ai-pixel-toolbar__meta">
            <span className={`ai-pixel-toolbar__mode ${editMode ? 'is-edit' : ''}`}>
              {editMode ? '편집 모드' : '탐색 모드'}
            </span>
            <span className="ai-pixel-toolbar__hint">
              {editMode
                ? '방 헤더 드래그로 이동 · 저장 또는 기본 배치 복원'
                : '드래그 이동 · 휠 줌 · 더블클릭 화면 맞춤 · 선택 포커스'}
            </span>
          </div>
          <div className="ai-pixel-toolbar__group">
            <button type="button" className="ai-pixel-toolbar__button" onClick={() => setEditMode((current) => !current)}>
              {editMode ? '편집 종료' : '편집 모드'}
            </button>
            <button type="button" className="ai-pixel-toolbar__button" onClick={() => saveLayout(layout)}>
              저장
            </button>
            <button type="button" className="ai-pixel-toolbar__button" onClick={resetToSavedLayout}>
              리셋
            </button>
            <button type="button" className="ai-pixel-toolbar__button" onClick={restoreDefaultLayout}>
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
              onClick={fitWorldToViewport}
            >
              화면 맞춤
            </button>
            <button
              type="button"
              className="ai-pixel-toolbar__button"
              onClick={() => centerOnSelection(selection)}
              disabled={!selection}
            >
              선택 포커스
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
