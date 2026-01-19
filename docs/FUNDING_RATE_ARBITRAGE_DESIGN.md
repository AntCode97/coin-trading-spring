# Funding Rate Arbitrage Engine Design

**Date**: 2026-01-18
**Status**: Design Phase

---

## 1. Overview

Funding Rate Arbitrage is a delta-neutral strategy that profits from the periodic funding payments between perpetual futures and spot positions.

### Key Concept

```
When Funding Rate > 0:
  - Longs pay Shorts
  - Strategy: Long Spot + Short Perp = Collect funding

When Funding Rate < 0:
  - Shorts pay Longs
  - Strategy: Short Spot + Long Perp = Collect funding (requires margin)
```

### Expected Returns

| Market Condition | Funding Rate | Annualized Return |
|------------------|--------------|-------------------|
| Bullish          | +0.01~0.05%  | 10-50% APY        |
| Neutral          | +0.005%      | 5-15% APY         |
| Bearish          | -0.01~0.05%  | 10-50% APY (if short spot available) |

---

## 2. Architecture

```
[FundingRateArbitrageEngine]
│
├── FundingRateMonitor
│   ├── Binance Futures API (funding rate every 8h)
│   ├── Bybit Futures API (funding rate every 8h)
│   └── Rate threshold detection
│
├── SpotHedger
│   ├── Bithumb spot position (existing)
│   └── Position size calculator
│
├── PerpManager
│   ├── Binance Futures client
│   ├── Bybit Futures client
│   └── Leverage manager (1x recommended)
│
├── DeltaCalculator
│   ├── Net exposure tracking
│   ├── Rebalancing trigger
│   └── Slippage buffer
│
├── PnLTracker
│   ├── Funding received/paid
│   ├── Trading costs
│   └── Net profit calculation
│
└── RiskManager
    ├── Max position size
    ├── Exchange health check
    └── Liquidation prevention
```

---

## 3. Entry/Exit Logic

### Entry Conditions

```kotlin
data class FundingOpportunity(
    val exchange: String,        // "BINANCE" or "BYBIT"
    val symbol: String,          // "BTCUSDT"
    val fundingRate: Double,     // 0.0001 = 0.01%
    val nextFundingTime: Instant,
    val annualizedRate: Double   // fundingRate * 3 * 365 * 100
)

fun shouldEnter(opportunity: FundingOpportunity): Boolean {
    return opportunity.annualizedRate >= MIN_ANNUALIZED_RATE  // 15% APY
        && opportunity.fundingRate > 0  // Only positive funding for now
        && hoursUntilFunding(opportunity) <= 2  // Enter close to funding
}
```

### Position Sizing

```kotlin
fun calculatePositionSize(
    availableCapital: BigDecimal,
    currentBtcPrice: BigDecimal,
    fundingRate: Double
): PositionSize {
    // Risk-adjusted position size
    val maxPositionKrw = availableCapital.multiply(MAX_CAPITAL_RATIO)  // 30%
    val btcQuantity = maxPositionKrw.divide(currentBtcPrice, 8, RoundingMode.DOWN)

    return PositionSize(
        spotQuantity = btcQuantity,
        perpQuantity = btcQuantity,  // Same size for delta neutral
        notionalKrw = maxPositionKrw
    )
}
```

### Exit Conditions

1. **Funding Collected**: Exit after 3 funding periods (24h) if rate remains favorable
2. **Rate Reversal**: Exit if funding rate drops below MIN_RATE (0.005%)
3. **Spread Opportunity**: Exit if spot-perp spread exceeds entry costs
4. **Emergency**: Exit if net exposure exceeds 5% (delta imbalance)

---

## 4. Implementation Phases

### Phase 1: Monitoring Only (Week 1-2)

```kotlin
@Component
class FundingRateMonitor(
    private val binanceClient: BinanceFuturesClient,
    private val slackNotifier: SlackNotifier
) {
    @Scheduled(fixedDelay = 60000)  // Every minute
    fun monitorFundingRates() {
        val rates = binanceClient.getFundingRates()

        rates.filter { it.annualizedRate >= 15.0 }
            .forEach { rate ->
                slackNotifier.sendSystemNotification(
                    "Funding Rate Alert",
                    "${rate.symbol}: ${rate.fundingRate}% (${rate.annualizedRate}% APY)"
                )
            }
    }
}
```

### Phase 2: Manual Execution with Alerts (Week 3-4)

- Alert when profitable opportunity detected
- Manual spot position on Bithumb
- Manual perp position on Binance
- Track P&L manually

### Phase 3: Semi-Automated (Week 5-6)

- Auto spot position on Bithumb (existing infrastructure)
- Manual perp position confirmation via Slack
- Auto position size calculation
- Auto P&L tracking

### Phase 4: Full Automation (Week 7+)

- Auto spot + perp execution
- Auto rebalancing
- Auto exit on rate reversal
- Full P&L reporting

---

## 5. API Requirements

### Binance Futures API

```kotlin
interface BinanceFuturesClient {
    // Public
    fun getFundingRates(): List<FundingRate>
    fun getMarkPrice(symbol: String): MarkPrice

    // Private (Phase 3+)
    fun getAccountInfo(): AccountInfo
    fun placeOrder(order: OrderRequest): OrderResponse
    fun getPositions(): List<Position>
}
```

### Required Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/fapi/v1/fundingRate` | GET | Historical funding rates |
| `/fapi/v1/premiumIndex` | GET | Current funding rate & next funding time |
| `/fapi/v1/account` | GET | Account info (auth required) |
| `/fapi/v1/order` | POST | Place order (auth required) |

---

## 6. Risk Management

### Position Limits

```kotlin
object FundingArbitrageConfig {
    const val MAX_CAPITAL_RATIO = 0.3       // 30% of total capital
    const val MAX_SINGLE_POSITION_KRW = 1_000_000  // 100만원 per position
    const val MIN_ANNUALIZED_RATE = 15.0    // 15% APY minimum
    const val MAX_DELTA_EXPOSURE = 0.05     // 5% net exposure trigger
    const val MAX_LEVERAGE = 1.0            // 1x leverage only
    const val SLIPPAGE_BUFFER = 0.002       // 0.2% slippage allowance
}
```

### Safety Checks

1. **Delta Neutrality**: Ensure spot and perp positions are equal
2. **Margin Monitoring**: Keep margin ratio above 50%
3. **Liquidation Prevention**: Close perp if margin drops below 30%
4. **API Health**: Check both exchanges before trading

---

## 7. P&L Calculation

```kotlin
data class FundingPnLReport(
    val totalFundingReceived: BigDecimal,
    val totalTradingCosts: BigDecimal,     // Spot + Perp fees
    val spotPnL: BigDecimal,               // Should be ~0 if delta neutral
    val perpPnL: BigDecimal,               // Should be ~0 if delta neutral
    val netPnL: BigDecimal,                // Funding - Costs
    val annualizedReturn: Double
)

fun calculatePnL(
    fundingPayments: List<FundingPayment>,
    trades: List<Trade>
): FundingPnLReport {
    val fundingReceived = fundingPayments.sumOf { it.amount }
    val tradingCosts = trades.sumOf { it.fee }

    return FundingPnLReport(
        totalFundingReceived = fundingReceived,
        totalTradingCosts = tradingCosts,
        netPnL = fundingReceived - tradingCosts,
        ...
    )
}
```

---

## 8. Database Schema

```sql
-- Funding rate history
CREATE TABLE funding_rates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exchange VARCHAR(20) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    funding_rate DOUBLE NOT NULL,
    funding_time TIMESTAMP NOT NULL,
    annualized_rate DOUBLE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_funding_symbol_time (symbol, funding_time)
);

-- Arbitrage positions
CREATE TABLE funding_arb_positions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol VARCHAR(20) NOT NULL,
    spot_exchange VARCHAR(20) NOT NULL,
    perp_exchange VARCHAR(20) NOT NULL,
    spot_quantity DOUBLE NOT NULL,
    perp_quantity DOUBLE NOT NULL,
    entry_funding_rate DOUBLE,
    entry_time TIMESTAMP NOT NULL,
    exit_time TIMESTAMP,
    total_funding_received DOUBLE DEFAULT 0,
    total_trading_cost DOUBLE DEFAULT 0,
    net_pnl DOUBLE,
    status VARCHAR(20) DEFAULT 'OPEN',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_arb_status (status)
);

-- Funding payments received
CREATE TABLE funding_payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    position_id BIGINT NOT NULL,
    exchange VARCHAR(20) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    payment_amount DOUBLE NOT NULL,
    funding_rate DOUBLE NOT NULL,
    payment_time TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (position_id) REFERENCES funding_arb_positions(id)
);
```

---

## 9. Next Steps

1. **Week 1**: Implement BinanceFuturesClient for funding rate monitoring
2. **Week 2**: Add Slack alerts for profitable opportunities
3. **Week 3**: Add manual position tracking in DB
4. **Week 4**: Implement semi-automated spot execution
5. **Week 5+**: Full automation with perp integration

---

## 10. References

- [Binance Funding Rate API](https://binance-docs.github.io/apidocs/futures/en/#get-funding-rate-history)
- [Funding Rate Arbitrage Explained](https://www.binance.com/en/blog/futures/how-to-profit-from-funding-rate-arbitrage-421499824684902574)
- [Delta Neutral Strategies](https://academy.binance.com/en/articles/what-is-a-funding-rate)

---

*This design document is part of the Quant Analysis Report recommendations.*
