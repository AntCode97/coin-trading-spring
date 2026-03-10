const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const mysql = require('mysql2/promise');

const KST_OFFSET_MS = 9 * 60 * 60 * 1000;
const DAY_MS = 24 * 60 * 60 * 1000;
const DEFAULT_LOOKBACK_DAYS = 14;
const DEFAULT_RECENT_LIMIT = 80;

function pad2(value) {
  return String(value).padStart(2, '0');
}

function toKstDateString(dateLike) {
  const raw = dateLike instanceof Date ? dateLike.getTime() : new Date(dateLike).getTime();
  const kst = new Date(raw + KST_OFFSET_MS);
  return `${kst.getUTCFullYear()}-${pad2(kst.getUTCMonth() + 1)}-${pad2(kst.getUTCDate())}`;
}

function parseMysqlInfo(text) {
  const result = {};
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const divider = trimmed.indexOf('=');
    if (divider <= 0) continue;
    const key = trimmed.slice(0, divider).trim();
    const value = trimmed.slice(divider + 1).trim();
    if (key && value) {
      result[key] = value;
    }
  }
  return result;
}

function previewText(value, maxLength = 220) {
  if (typeof value !== 'string') return '';
  const normalized = value.replace(/\s+/g, ' ').trim();
  if (normalized.length <= maxLength) return normalized;
  return `${normalized.slice(0, maxLength)}...`;
}

function toIsoString(value) {
  if (!value) return null;
  const date = value instanceof Date ? value : new Date(value);
  if (!Number.isFinite(date.getTime())) return null;
  return date.toISOString();
}

function toNumber(value, fallback = 0) {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : fallback;
}

function summarizeTrades(trades) {
  const wins = trades.filter((trade) => trade.realizedPnl > 0).length;
  const losses = trades.filter((trade) => trade.realizedPnl < 0).length;
  const totalPnlKrw = trades.reduce((sum, trade) => sum + trade.realizedPnl, 0);
  const avgPnlPercent = trades.length > 0
    ? trades.reduce((sum, trade) => sum + trade.realizedPnlPercent, 0) / trades.length
    : 0;
  const avgHoldingMinutes = trades.length > 0
    ? trades.reduce((sum, trade) => sum + trade.holdingMinutes, 0) / trades.length
    : 0;

  return {
    totalTrades: trades.length,
    wins,
    losses,
    totalPnlKrw: Number(totalPnlKrw.toFixed(2)),
    avgPnlPercent: Number(avgPnlPercent.toFixed(4)),
    winRate: trades.length > 0 ? Number(((wins / trades.length) * 100).toFixed(2)) : 0,
    avgHoldingMinutes: Number(avgHoldingMinutes.toFixed(2)),
  };
}

function aggregateDailyTrend(trades) {
  const grouped = new Map();
  for (const trade of trades) {
    const bucket = grouped.get(trade.tradeDateKst) ?? [];
    bucket.push(trade);
    grouped.set(trade.tradeDateKst, bucket);
  }

  return [...grouped.entries()]
    .map(([tradeDate, items]) => ({
      tradeDate,
      ...summarizeTrades(items),
    }))
    .sort((left, right) => right.tradeDate.localeCompare(left.tradeDate));
}

function aggregateByExitReason(trades) {
  const grouped = new Map();
  for (const trade of trades) {
    const key = trade.exitReason || 'UNKNOWN';
    const bucket = grouped.get(key) ?? [];
    bucket.push(trade);
    grouped.set(key, bucket);
  }

  return [...grouped.entries()]
    .map(([exitReason, items]) => ({
      exitReason,
      ...summarizeTrades(items),
    }))
    .sort((left, right) => right.totalTrades - left.totalTrades);
}

function aggregateByMarket(trades) {
  const grouped = new Map();
  for (const trade of trades) {
    const bucket = grouped.get(trade.market) ?? [];
    bucket.push(trade);
    grouped.set(trade.market, bucket);
  }

  return [...grouped.entries()]
    .map(([market, items]) => ({
      market,
      ...summarizeTrades(items),
    }))
    .sort((left, right) => {
      if (Math.abs(right.totalPnlKrw - left.totalPnlKrw) > 1e-9) {
        return right.totalPnlKrw - left.totalPnlKrw;
      }
      return right.totalTrades - left.totalTrades;
    });
}

class DesktopReviewDbClient {
  constructor(app) {
    this.app = app;
    this.pool = null;
    this.connectionKey = null;
    this.cachedConfig = null;
  }

  resolveMysqlInfoPath() {
    const envPath = process.env.COIN_TRADING_MYSQL_INFO_PATH?.trim();
    const appPath = this.app?.getAppPath?.() || process.cwd();
    const candidates = [
      envPath,
      path.resolve(process.cwd(), '.mysql_info'),
      path.resolve(process.cwd(), '../.mysql_info'),
      path.resolve(process.cwd(), '../../.mysql_info'),
      path.resolve(appPath, '.mysql_info'),
      path.resolve(appPath, '../.mysql_info'),
      path.resolve(appPath, '../../.mysql_info'),
      path.resolve(__dirname, '../../../.mysql_info'),
      path.resolve(os.homedir(), 'workspace/personal/coin-trading-spring/.mysql_info'),
    ].filter(Boolean);

    for (const candidate of candidates) {
      try {
        if (candidate && fs.existsSync(candidate)) {
          return candidate;
        }
      } catch {
        // ignore
      }
    }
    return null;
  }

  loadConfig() {
    const infoPath = this.resolveMysqlInfoPath();
    if (!infoPath) {
      throw new Error('.mysql_info 파일을 찾을 수 없습니다.');
    }
    const raw = fs.readFileSync(infoPath, 'utf8');
    const parsed = parseMysqlInfo(raw);
    const host = parsed.MYSQL_HOST?.trim();
    const port = Number(parsed.MYSQL_PORT);
    const user = parsed.MYSQL_USER?.trim();
    const password = parsed.MYSQL_PASSWORD?.trim();
    const database = parsed.MYSQL_DATABASE?.trim();

    if (!host || !port || !user || !password || !database) {
      throw new Error('.mysql_info 형식이 올바르지 않습니다.');
    }

    return {
      infoPath,
      host,
      port,
      user,
      password,
      database,
    };
  }

  async getPool() {
    const config = this.loadConfig();
    const nextKey = `${config.host}:${config.port}/${config.database}/${config.user}`;
    if (this.pool && this.connectionKey === nextKey) {
      return { pool: this.pool, config: this.cachedConfig };
    }

    if (this.pool) {
      await this.pool.end().catch(() => {});
    }

    this.pool = mysql.createPool({
      host: config.host,
      port: config.port,
      user: config.user,
      password: config.password,
      database: config.database,
      connectionLimit: 4,
      timezone: 'Z',
      decimalNumbers: true,
      enableKeepAlive: true,
    });
    this.connectionKey = nextKey;
    this.cachedConfig = config;
    return { pool: this.pool, config };
  }

  async getStatus() {
    try {
      const { pool, config } = await this.getPool();
      await pool.query('SELECT 1');
      return {
        connected: true,
        hostLabel: `${config.host}:${config.port}`,
        database: config.database,
        infoPath: config.infoPath,
      };
    } catch (error) {
      return {
        connected: false,
        hostLabel: null,
        database: null,
        infoPath: this.resolveMysqlInfoPath(),
        error: error instanceof Error ? error.message : 'MySQL 연결 실패',
      };
    }
  }

  async getAiReviewBundle(options = {}) {
    const strategyCodePrefix = typeof options.strategyCodePrefix === 'string' && options.strategyCodePrefix.trim()
      ? options.strategyCodePrefix.trim().toUpperCase()
      : 'AI_SCALP_TRADER';
    const lookbackDays = Math.max(7, Math.min(60, Number(options.lookbackDays) || DEFAULT_LOOKBACK_DAYS));
    const recentLimit = Math.max(20, Math.min(200, Number(options.recentLimit) || DEFAULT_RECENT_LIMIT));
    const targetDate = typeof options.targetDate === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(options.targetDate)
      ? options.targetDate
      : toKstDateString(Date.now() - DAY_MS);

    const targetStart = new Date(`${targetDate}T00:00:00+09:00`);
    const targetEnd = new Date(targetStart.getTime() + DAY_MS);
    const historyStart = new Date(targetEnd.getTime() - lookbackDays * DAY_MS);

    const { pool } = await this.getPool();

    const [targetRows] = await pool.query(`
      SELECT
        id AS tradeId,
        market,
        status,
        average_entry_price AS averageEntryPrice,
        average_exit_price AS averageExitPrice,
        entry_quantity AS entryQuantity,
        target_amount_krw AS targetAmountKrw,
        realized_pnl AS realizedPnl,
        realized_pnl_percent AS realizedPnlPercent,
        recommendation_reason AS recommendationReason,
        exit_reason AS exitReason,
        strategy_code AS strategyCode,
        created_at AS createdAt,
        closed_at AS closedAt
      FROM guided_trades
      WHERE strategy_code LIKE ?
        AND status = 'CLOSED'
        AND closed_at >= ?
        AND closed_at < ?
      ORDER BY closed_at DESC
      LIMIT ?
    `, [`${strategyCodePrefix}%`, targetStart, targetEnd, recentLimit]);

    const [historyRows] = await pool.query(`
      SELECT
        id AS tradeId,
        market,
        status,
        average_entry_price AS averageEntryPrice,
        average_exit_price AS averageExitPrice,
        entry_quantity AS entryQuantity,
        target_amount_krw AS targetAmountKrw,
        realized_pnl AS realizedPnl,
        realized_pnl_percent AS realizedPnlPercent,
        recommendation_reason AS recommendationReason,
        exit_reason AS exitReason,
        strategy_code AS strategyCode,
        created_at AS createdAt,
        closed_at AS closedAt
      FROM guided_trades
      WHERE strategy_code LIKE ?
        AND status = 'CLOSED'
        AND closed_at >= ?
        AND closed_at < ?
      ORDER BY closed_at DESC
      LIMIT ?
    `, [`${strategyCodePrefix}%`, historyStart, targetEnd, recentLimit]);

    const [openRows] = await pool.query(`
      SELECT
        id AS tradeId,
        market,
        status,
        average_entry_price AS averageEntryPrice,
        remaining_quantity AS remainingQuantity,
        stop_loss_price AS stopLossPrice,
        take_profit_price AS takeProfitPrice,
        created_at AS createdAt
      FROM guided_trades
      WHERE strategy_code LIKE ?
        AND status IN ('OPEN', 'PENDING_ENTRY')
      ORDER BY created_at DESC
      LIMIT 20
    `, [`${strategyCodePrefix}%`]);

    const [keyValueRows] = await pool.query(`
      SELECT
        kv_key AS kvKey,
        kv_value AS kvValue,
        category,
        updated_at AS updatedAt
      FROM key_value_store
      WHERE category IN ('risk', 'llm', 'trading', 'volumesurge', 'memescalper', 'deployment')
         OR kv_key LIKE 'llm.%'
         OR kv_key LIKE 'risk.%'
         OR kv_key LIKE 'volumesurge.%'
         OR kv_key LIKE 'memescalper.%'
      ORDER BY updated_at DESC
      LIMIT 40
    `);

    const [promptRows] = await pool.query(`
      SELECT
        prompt_name AS promptName,
        prompt_type AS promptType,
        version,
        is_active AS isActive,
        created_by AS createdBy,
        performance_score AS performanceScore,
        usage_count AS usageCount,
        updated_at AS updatedAt
      FROM llm_prompts
      ORDER BY updated_at DESC
      LIMIT 12
    `);

    const [auditRows] = await pool.query(`
      SELECT
        event_type AS eventType,
        action,
        triggered_by AS triggeredBy,
        reason,
        output_data AS outputData,
        created_at AS createdAt
      FROM audit_logs
      WHERE event_type IN ('LLM_OPTIMIZATION', 'GUIDED_AUTOPILOT', 'MEME_SCALPER_REFLECTION', 'VOLUME_SURGE_REFLECTION')
        AND created_at >= ?
      ORDER BY created_at DESC
      LIMIT 20
    `, [historyStart]);

    const normalizeTrade = (row) => {
      const createdAt = toIsoString(row.createdAt);
      const closedAt = toIsoString(row.closedAt);
      const startMs = createdAt ? new Date(createdAt).getTime() : 0;
      const endMs = closedAt ? new Date(closedAt).getTime() : startMs;
      return {
        tradeId: Number(row.tradeId),
        market: row.market,
        status: row.status,
        averageEntryPrice: toNumber(row.averageEntryPrice),
        averageExitPrice: toNumber(row.averageExitPrice),
        entryQuantity: toNumber(row.entryQuantity),
        targetAmountKrw: toNumber(row.targetAmountKrw),
        realizedPnl: toNumber(row.realizedPnl),
        realizedPnlPercent: toNumber(row.realizedPnlPercent),
        recommendationReason: previewText(row.recommendationReason, 260),
        exitReason: row.exitReason || null,
        strategyCode: row.strategyCode || null,
        createdAt,
        closedAt,
        holdingMinutes: startMs > 0 && endMs >= startMs ? Number(((endMs - startMs) / 60_000).toFixed(2)) : 0,
        tradeDateKst: toKstDateString(closedAt || createdAt || Date.now()),
      };
    };

    const targetTrades = targetRows.map(normalizeTrade);
    const historyTrades = historyRows.map(normalizeTrade);
    const openPositions = openRows.map((row) => ({
      tradeId: Number(row.tradeId),
      market: row.market,
      status: row.status,
      averageEntryPrice: toNumber(row.averageEntryPrice),
      remainingQuantity: toNumber(row.remainingQuantity),
      stopLossPrice: toNumber(row.stopLossPrice),
      takeProfitPrice: toNumber(row.takeProfitPrice),
      createdAt: toIsoString(row.createdAt),
    }));

    return {
      generatedAt: new Date().toISOString(),
      targetDate,
      strategyCodePrefix,
      summary: summarizeTrades(targetTrades),
      targetTrades,
      historyTrades,
      openPositions,
      dailyTrend: aggregateDailyTrend(historyTrades),
      marketBreakdown: aggregateByMarket(targetTrades),
      exitReasonBreakdown: aggregateByExitReason(targetTrades),
      keyValues: keyValueRows.map((row) => ({
        key: row.kvKey,
        value: String(row.kvValue ?? ''),
        category: row.category || null,
        updatedAt: toIsoString(row.updatedAt),
      })),
      prompts: promptRows.map((row) => ({
        promptName: row.promptName,
        promptType: row.promptType,
        version: Number(row.version),
        isActive: Boolean(row.isActive),
        createdBy: row.createdBy,
        performanceScore: row.performanceScore == null ? null : toNumber(row.performanceScore),
        usageCount: Number(row.usageCount),
        updatedAt: toIsoString(row.updatedAt),
      })),
      auditLogs: auditRows.map((row) => ({
        eventType: row.eventType,
        action: row.action,
        triggeredBy: row.triggeredBy || null,
        reason: row.reason || null,
        outputPreview: previewText(row.outputData, 260),
        createdAt: toIsoString(row.createdAt),
      })),
    };
  }
}

module.exports = { DesktopReviewDbClient };
