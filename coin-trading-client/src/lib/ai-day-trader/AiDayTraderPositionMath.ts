import type { GuidedTradePosition } from '../../api';

const BITHUMB_FEE_RATE = 0.0004;

export function isPlausiblePositionEntry(entryPrice: number, currentPrice: number): boolean {
  if (!Number.isFinite(entryPrice) || entryPrice <= 0 || !Number.isFinite(currentPrice) || currentPrice <= 0) {
    return false;
  }
  return entryPrice >= currentPrice * 0.5 && entryPrice <= currentPrice * 1.5;
}

export function estimatePositionEntryPrice(position: GuidedTradePosition): number {
  const currentPrice = position.currentPrice;
  if (!Number.isFinite(currentPrice) || currentPrice <= 0) {
    return position.averageEntryPrice;
  }

  const candidates: number[] = [];
  if (
    Number.isFinite(position.stopLossPrice) &&
    Number.isFinite(position.takeProfitPrice) &&
    position.stopLossPrice > 0 &&
    position.takeProfitPrice > position.stopLossPrice
  ) {
    candidates.push((position.stopLossPrice + position.takeProfitPrice) / 2);
  }
  if (Number.isFinite(position.stopLossPrice) && position.stopLossPrice > 0 && position.stopLossPrice < currentPrice) {
    candidates.push((position.stopLossPrice + currentPrice) / 2);
  }
  if (Number.isFinite(position.takeProfitPrice) && position.takeProfitPrice > currentPrice) {
    candidates.push((position.takeProfitPrice + currentPrice) / 2);
  }

  const plausibleCandidate = candidates.find((candidate) => isPlausiblePositionEntry(candidate, currentPrice));
  return plausibleCandidate ?? currentPrice;
}

export function calculateFeeAdjustedReturnPercent(entryPrice: number, currentPrice: number): number {
  if (!Number.isFinite(entryPrice) || entryPrice <= 0 || !Number.isFinite(currentPrice) || currentPrice <= 0) {
    return 0;
  }
  const grossReturn = (currentPrice - entryPrice) / entryPrice;
  const feeReturn = BITHUMB_FEE_RATE * (1 + currentPrice / entryPrice);
  return (grossReturn - feeReturn) * 100;
}

export function normalizePosition(position: GuidedTradePosition): GuidedTradePosition {
  const currentPrice = position.currentPrice;
  const entryPrice = position.averageEntryPrice;
  if (!isPlausiblePositionEntry(entryPrice, currentPrice)) {
    const estimatedEntryPrice = estimatePositionEntryPrice(position);
    return {
      ...position,
      averageEntryPrice: estimatedEntryPrice,
      unrealizedPnlPercent: calculateFeeAdjustedReturnPercent(estimatedEntryPrice, currentPrice),
    };
  }
  return {
    ...position,
    unrealizedPnlPercent: calculateFeeAdjustedReturnPercent(entryPrice, currentPrice),
  };
}
