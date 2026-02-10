package com.ant.cointrading.controller

import com.ant.cointrading.config.TradingProperties
import com.ant.cointrading.portfolio.*
import org.springframework.web.bind.annotation.*

/**
 * 포트폴리오 관리 REST API
 */
@RestController
@RequestMapping("/api/portfolio")
class PortfolioController(
    private val portfolioOptimizer: PortfolioOptimizer,
    private val kellySizer: AdaptiveKellySizer,
    private val hrpAllocator: HRPAllocator,
    private val blackLittermanModel: BlackLittermanModel,
    private val factorModelCalculator: FactorModelCalculator,
    private val tacticalAssetAllocator: TacticalAssetAllocator,
    private val robustOptimizer: RobustOptimizer,
    private val executionOptimizer: ExecutionOptimizer,
    private val tradingProperties: TradingProperties
) {

    /**
     * 샤프 비율 최적화
     */
    @GetMapping("/optimize/sharpe")
    fun optimizeSharpe(
        @RequestParam(defaultValue = "90") lookbackDays: Int
    ): Map<String, Any> {
        val assets = tradingProperties.markets
        val result = portfolioOptimizer.maximizeSharpeRatio(assets, lookbackDays)

        return success(
            "method" to "Sharpe Ratio Maximization",
            "assets" to assets,
            "weights" to result.weights.toList(),
            "expectedReturn" to result.expectedReturn,
            "portfolioRisk" to result.portfolioRisk,
            "sharpeRatio" to result.sharpeRatio,
            "lookbackDays" to lookbackDays
        )
    }

    /**
     * 리스크 패리티 최적화
     */
    @GetMapping("/optimize/risk-parity")
    fun optimizeRiskParity(
        @RequestParam(defaultValue = "90") lookbackDays: Int
    ): Map<String, Any> {
        val assets = tradingProperties.markets
        val result = portfolioOptimizer.calculateRiskParity(assets, lookbackDays)

        return success(
            "method" to "Risk Parity",
            "assets" to assets,
            "weights" to result.weights.toList(),
            "expectedReturn" to result.expectedReturn,
            "portfolioRisk" to result.portfolioRisk,
            "sharpeRatio" to result.sharpeRatio,
            "lookbackDays" to lookbackDays
        )
    }

    /**
     * HRP (Hierarchical Risk Parity) 최적화
     */
    @GetMapping("/optimize/hrp")
    fun optimizeHRP(
        @RequestParam(defaultValue = "90") lookbackDays: Int
    ): Map<String, Any> {
        val assets = tradingProperties.markets
        val result = hrpAllocator.calculateHRPWeights(assets, lookbackDays)

        return success(
            "method" to "Hierarchical Risk Parity",
            "assets" to assets,
            "weights" to result.weights.toList(),
            "clusters" to result.clusters,
            "linkageDistance" to result.linkageDistance,
            "lookbackDays" to lookbackDays
        )
    }

    /**
     * CRP (Clustered Risk Parity) 최적화
     */
    @GetMapping("/optimize/crp")
    fun optimizeCRP(
        @RequestParam(defaultValue = "90") lookbackDays: Int,
        @RequestParam(defaultValue = "3") nClusters: Int
    ): Map<String, Any> {
        val assets = tradingProperties.markets
        val result = hrpAllocator.calculateClusteredRiskParity(assets, lookbackDays, nClusters)

        return success(
            "method" to "Clustered Risk Parity",
            "assets" to assets,
            "weights" to result.weights.toList(),
            "clusters" to result.clusters,
            "nClusters" to nClusters,
            "lookbackDays" to lookbackDays
        )
    }

    /**
     * Efficient Frontier 조회
     */
    @GetMapping("/efficient-frontier")
    fun getEfficientFrontier(
        @RequestParam(defaultValue = "90") lookbackDays: Int,
        @RequestParam(defaultValue = "20") nPoints: Int
    ): Map<String, Any> {
        val assets = tradingProperties.markets
        val frontier = portfolioOptimizer.calculateEfficientFrontier(assets, lookbackDays, nPoints)

        return success(
            "assets" to assets,
            "frontier" to frontier.map { point ->
                mapOf(
                    "weights" to point.weights.toList(),
                    "expectedReturn" to point.expectedReturn,
                    "risk" to point.risk
                )
            },
            "lookbackDays" to lookbackDays
        )
    }

    /**
     * Kelly Criterion 포지션 사이징
     */
    @GetMapping("/kelly/position-size")
    fun getKellyPositionSize(
        @RequestParam(defaultValue = "0.55") winRate: Double,
        @RequestParam(defaultValue = "2.0") riskReward: Double,
        @RequestParam(defaultValue = "0.5") confidence: Double,
        @RequestParam(defaultValue = "NORMAL") marketRegime: String
    ): Map<String, Any> {
        val positionSize = kellySizer.calculatePositionSize(
            winRate = winRate,
            riskReward = riskReward,
            confidence = confidence,
            marketRegime = marketRegime
        )

        return success(
            "positionSize" to positionSize,
            "winRate" to winRate,
            "riskReward" to riskReward,
            "confidence" to confidence,
            "marketRegime" to marketRegime,
            "asPercentage" to "${(positionSize * 100).toInt()}%"
        )
    }

    /**
     * 리스크 패리티 기반 포지션 사이징
     */
    @GetMapping("/kelly/position-size-risk-parity")
    fun getKellyPositionSizeWithRiskParity(
        @RequestParam(defaultValue = "0.5") assetVolatility: Double,
        @RequestParam(defaultValue = "0.2") portfolioVolatility: Double,
        @RequestParam(defaultValue = "0.5") signalStrength: Double,
        @RequestParam(defaultValue = "1000000") totalCapital: Double,
        @RequestParam(defaultValue = "0.02") riskBudget: Double
    ): Map<String, Any> {
        val positionSize = kellySizer.calculatePositionSizeWithRiskParity(
            assetVolatility = assetVolatility,
            portfolioVolatility = portfolioVolatility,
            signalStrength = signalStrength,
            totalCapital = totalCapital,
            riskBudget = riskBudget
        )

        return success(
            "positionSize" to positionSize,
            "positionSizeWon" to positionSize.toLong(),
            "assetVolatility" to assetVolatility,
            "signalStrength" to signalStrength,
            "asPercentageOfCapital" to "${((positionSize / totalCapital) * 100).toInt()}%"
        )
    }

    /**
     * Kelly Criterion 상태 조회
     */
    @GetMapping("/kelly/status")
    fun getKellyStatus(): Map<String, Any> {
        val status = kellySizer.getKellyStatus()

        return success(
            "ewmaWinRate" to status.ewmaWinRate,
            "ewmaRiskReward" to status.ewmaRiskReward,
            "rawKelly" to status.rawKelly,
            "halfKelly" to status.halfKelly,
            "recommendedPositionSize" to status.recommendedPositionSize,
            "historySize" to status.historySize
        )
    }

    /**
     * 트레이딩 히스토리 로드
     */
    @PostMapping("/kelly/load-history")
    fun loadKellyHistory(
        @RequestParam(defaultValue = "30") lookbackDays: Int
    ): Map<String, Any> {
        kellySizer.loadHistoryFromDB(lookbackDays)

        return success(
            "message" to "트레이딩 히스토리 로드 완료",
            "lookbackDays" to lookbackDays
        )
    }

    /**
     * Kelly 히스토리 초기화
     */
    @PostMapping("/kelly/clear-history")
    fun clearKellyHistory(): Map<String, Any> {
        kellySizer.clearHistory()

        return success(
            "message" to "트레이딩 히스토리 초기화 완료"
        )
    }

    /**
     * 포트폴리오 요약 정보
     */
    @GetMapping("/summary")
    fun getPortfolioSummary(
        @RequestParam(defaultValue = "90") lookbackDays: Int
    ): Map<String, Any> {
        val assets = tradingProperties.markets

        // 여러 최적화 방법 비교
        val sharpeResult = portfolioOptimizer.maximizeSharpeRatio(assets, lookbackDays)
        val riskParityResult = portfolioOptimizer.calculateRiskParity(assets, lookbackDays)
        val hrpResult = hrpAllocator.calculateHRPWeights(assets, lookbackDays)

        return success(
            "assets" to assets,
            "lookbackDays" to lookbackDays,
            "comparison" to mapOf(
                "sharpeRatio" to mapOf(
                    "weights" to sharpeResult.weights.toList(),
                    "expectedReturn" to sharpeResult.expectedReturn,
                    "risk" to sharpeResult.portfolioRisk,
                    "sharpeRatio" to sharpeResult.sharpeRatio
                ),
                "riskParity" to mapOf(
                    "weights" to riskParityResult.weights.toList(),
                    "expectedReturn" to riskParityResult.expectedReturn,
                    "risk" to riskParityResult.portfolioRisk,
                    "sharpeRatio" to riskParityResult.sharpeRatio
                ),
                "hrp" to mapOf(
                    "weights" to hrpResult.weights.toList(),
                    "clusters" to hrpResult.clusters,
                    "linkageDistance" to hrpResult.linkageDistance
                )
            )
        )
    }

    // ========== Black-Litterman APIs ==========

    /**
     * Black-Litterman 수익률 계산
     */
    @GetMapping("/black-litterman/returns")
    fun getBlackLittermanReturns(
        @RequestParam(defaultValue = "90") lookbackDays: Int,
        @RequestParam(defaultValue = "0.025") tau: Double
    ): Map<String, Any> {
        val assets = tradingProperties.markets

        val result = blackLittermanModel.calculateBlackLittermanReturns(
            assets,
            views = emptyList(),  // 뷰 없이 균형점만 사용
            marketCaps = null,
            lookbackDays = lookbackDays,
            tau = tau
        )

        return success(
            "assets" to assets,
            "lookbackDays" to lookbackDays,
            "tau" to tau,
            "impliedReturns" to result.impliedReturns.toList(),
            "blReturns" to result.expectedReturns.toList()
        )
    }

    /**
     * 컨플루언스 기반 뷰 조회 (더미)
     */
    @GetMapping("/views/confluence")
    fun getConfluenceViews(
        @RequestParam(defaultValue = "90") lookbackDays: Int
    ): Map<String, Any> {
        val assets = tradingProperties.markets

        return success(
            "assets" to assets,
            "message" to "컨플루언스 뷰 생성 기능 (준비 중)",
            "views" to emptyList<BlackLittermanModel.View>()
        )
    }

    /**
     * 모멘텀 기반 뷰 조회 (더미)
     */
    @GetMapping("/views/momentum")
    fun getMomentumViews(
        @RequestParam(defaultValue = "30") lookbackDays: Int
    ): Map<String, Any> {
        val assets = tradingProperties.markets

        return success(
            "assets" to assets,
            "lookbackDays" to lookbackDays,
            "message" to "모멘텀 뷰 생성 기능 (준비 중)",
            "views" to emptyList<BlackLittermanModel.View>()
        )
    }

    /**
     * 상대 뷰 조회 (더미)
     */
    @GetMapping("/views/relative")
    fun getRelativeViews(
        @RequestParam(defaultValue = "7") lookbackDays: Int
    ): Map<String, Any> {
        val assets = tradingProperties.markets

        return success(
            "assets" to assets,
            "lookbackDays" to lookbackDays,
            "message" to "상대 뷰 생성 기능 (준비 중)",
            "views" to emptyList<BlackLittermanModel.View>()
        )
    }

    /**
     * BL + Risk Parity 결합 최적화
     */
    @GetMapping("/optimize/bl-risk-parity")
    fun optimizeBLRiskParity(
        @RequestParam(defaultValue = "90") lookbackDays: Int,
        @RequestParam(defaultValue = "0.025") tau: Double
    ): Map<String, Any> {
        val assets = tradingProperties.markets

        // BL 수익률 계산 (뷰 없이)
        val blResult = blackLittermanModel.calculateBlackLittermanReturns(
            assets,
            views = emptyList(),
            marketCaps = null,
            lookbackDays = lookbackDays,
            tau = tau
        )

        // Risk Parity 가중치 계산
        val rpResult = portfolioOptimizer.calculateRiskParity(assets, lookbackDays)

        return success(
            "method" to "Black-Litterman + Risk Parity",
            "assets" to assets,
            "blReturns" to blResult.expectedReturns.toList(),
            "weights" to rpResult.weights.toList(),
            "expectedReturn" to rpResult.expectedReturn,
            "portfolioRisk" to rpResult.portfolioRisk,
            "sharpeRatio" to rpResult.sharpeRatio
        )
    }

    // ========== Factor Model APIs ==========

    /**
     * CAPM 베타 계산
     */
    @GetMapping("/capm/{asset}")
    fun getCAPM(
        @PathVariable asset: String,
        @RequestParam(defaultValue = "BTC_KRW") marketProxy: String,
        @RequestParam(defaultValue = "90") lookbackDays: Int
    ): Map<String, Any> {
        val result = factorModelCalculator.calculateCAPM(asset, marketProxy, lookbackDays)

        return success(
            "asset" to asset,
            "marketProxy" to marketProxy,
            "alpha" to result.alpha,
            "beta" to result.beta,
            "rSquared" to result.rSquared,
            "idiosyncraticVolatility" to result.idiosyncraticVol,
            "expectedReturn" to result.expectedReturn,
            "lookbackDays" to lookbackDays
        )
    }

    /**
     * 전체 자산 CAPM 결과 조회
     */
    @GetMapping("/capm")
    fun getAllCAPM(
        @RequestParam(defaultValue = "BTC_KRW") marketProxy: String,
        @RequestParam(defaultValue = "90") lookbackDays: Int
    ): Map<String, Any> {
        val assets = tradingProperties.markets
        val results = factorModelCalculator.calculateMultipleCAPM(assets, marketProxy, lookbackDays)

        return success(
            "assets" to assets,
            "marketProxy" to marketProxy,
            "results" to results.mapValues { (_, result) ->
                mapOf(
                    "alpha" to result.alpha,
                    "beta" to result.beta,
                    "rSquared" to result.rSquared,
                    "idiosyncraticVolatility" to result.idiosyncraticVol,
                    "expectedReturn" to result.expectedReturn
                )
            },
            "lookbackDays" to lookbackDays
        )
    }

    /**
     * Fama-French 3-Factor Model
     */
    @GetMapping("/fama-french/{asset}")
    fun getFamaFrench(
        @PathVariable asset: String,
        @RequestParam(defaultValue = "90") lookbackDays: Int
    ): Map<String, Any> {
        val assets = tradingProperties.markets
        val result = factorModelCalculator.calculateFamaFrench(asset, assets, lookbackDays)

        return success(
            "asset" to asset,
            "alpha" to result.alpha,
            "betaMKT" to result.betaMKT,
            "betaSMB" to result.betaSMB,
            "betaHML" to result.betaHML,
            "rSquared" to result.rSquared,
            "expectedReturn" to result.expectedReturn,
            "lookbackDays" to lookbackDays
        )
    }

    // ========== Tactical Asset Allocation APIs ==========

    /**
     * 모멘텀 점수 조회
     */
    @GetMapping("/momentum-scores")
    fun getMomentumScores(
        @RequestParam(defaultValue = "30") lookbackPeriod: Int,
        @RequestParam(defaultValue = "10") halflife: Int
    ): Map<String, Any> {
        val assets = tradingProperties.markets
        val scores = tacticalAssetAllocator.calculateMomentumScores(assets, lookbackPeriod, halflife)

        return success(
            "assets" to assets,
            "lookbackPeriod" to lookbackPeriod,
            "halflife" to halflife,
            "scores" to scores.map { score ->
                mapOf(
                    "asset" to score.asset,
                    "rawScore" to score.score,
                    "normalizedScore" to score.normalizedScore,
                    "rank" to score.rank
                )
            }
        )
    }

    /**
     * 시장 레짐 감지
     */
    @GetMapping("/market-regime")
    fun getMarketRegime(
        @RequestParam(defaultValue = "30") lookbackDays: Int
    ): Map<String, Any> {
        val assets = tradingProperties.markets
        val regime = tacticalAssetAllocator.detectMarketRegime(assets, lookbackDays)

        return success(
            "regime" to regime.name,
            "description" to when (regime) {
                TacticalAssetAllocator.MarketRegime.BULL -> "강세장: 상승 추세"
                TacticalAssetAllocator.MarketRegime.BEAR -> "약세장: 하락 추세"
                TacticalAssetAllocator.MarketRegime.SIDEWAYS -> "횡보장: 방향성 없음"
                TacticalAssetAllocator.MarketRegime.HIGH_VOL -> "고변동: 급등락"
                TacticalAssetAllocator.MarketRegime.LOW_VOL -> "저변동: 안정적"
            },
            "lookbackDays" to lookbackDays
        )
    }

    /**
     * 전술적 배분 계산
     */
    @GetMapping("/tactical-allocation")
    fun getTacticalAllocation(
        @RequestParam(defaultValue = "90") lookbackDays: Int,
        @RequestParam(defaultValue = "SHARPE") strategicMethod: String
    ): Map<String, Any> {
        val assets = tradingProperties.markets
        val result = tacticalAssetAllocator.calculateTacticalAllocation(assets, lookbackDays, strategicMethod)

        return success(
            "assets" to assets,
            "strategicMethod" to strategicMethod,
            "currentRegime" to result.currentRegime.name,
            "strategicWeights" to result.strategicWeights,
            "momentumScores" to result.momentumScores,
            "regimeAdjustments" to result.regimeAdjustments,
            "tacticalWeights" to result.tacticalWeights,
            "lookbackDays" to lookbackDays
        )
    }

    /**
     * 변동성 타겟팅
     */
    @GetMapping("/volatility-targeting")
    fun getVolatilityTargeting(
        @RequestParam(defaultValue = "90") lookbackDays: Int,
        @RequestParam(defaultValue = "0.15") targetVolatility: Double,
        @RequestParam(defaultValue = "1.5") maxLeverage: Double
    ): Map<String, Any> {
        val assets = tradingProperties.markets

        // 기본 가중치 (균등)
        val baseWeights = assets.associateWith { 1.0 / assets.size }

        val adjustedWeights = tacticalAssetAllocator.volatilityTargeting(
            currentWeights = baseWeights,
            assets = assets,
            lookbackDays = lookbackDays,
            targetVolatility = targetVolatility,
            maxLeverage = maxLeverage
        )

        return success(
            "assets" to assets,
            "baseWeights" to baseWeights,
            "adjustedWeights" to adjustedWeights,
            "targetVolatility" to targetVolatility,
            "maxLeverage" to maxLeverage,
            "lookbackDays" to lookbackDays
        )
    }

    // ========== Robust Optimization APIs ==========

    /**
     * Robust Mean-Variance Optimization
     */
    @GetMapping("/robust-optimization")
    fun getRobustOptimization(
        @RequestParam(defaultValue = "90") lookbackDays: Int,
        @RequestParam(defaultValue = "0.1") uncertaintyRadius: Double,
        @RequestParam(required = false) targetReturn: Double?
    ): Map<String, Any> {
        val assets = tradingProperties.markets
        val result = robustOptimizer.robustMeanVarianceOptimization(
            assets, lookbackDays, uncertaintyRadius, targetReturn
        )

        return success(
            "assets" to assets,
            "weights" to result.weights.toList(),
            "expectedReturn" to result.expectedReturn,
            "trueRisk" to result.trueRisk,
            "worstCaseRisk" to result.worstCaseRisk,
            "robustnessGap" to result.robustnessGap,
            "uncertaintyRadius" to result.uncertaintyRadius,
            "lookbackDays" to lookbackDays
        )
    }

    /**
     * Resampled Efficient Frontier
     */
    @GetMapping("/resampled-frontier")
    fun getResampledFrontier(
        @RequestParam(defaultValue = "90") lookbackDays: Int,
        @RequestParam(defaultValue = "100") nResamples: Int,
        @RequestParam(defaultValue = "20") nPoints: Int
    ): Map<String, Any> {
        val assets = tradingProperties.markets
        val result = robustOptimizer.resampledEfficientFrontier(
            assets, lookbackDays, nResamples, nPoints
        )

        return success(
            "assets" to assets,
            "nResamples" to result.nResamples,
            "averageWeights" to result.averageWeights,
            "weightStdDevs" to result.weightStdDevs,
            "frontier" to result.frontierPoints.map { point ->
                mapOf(
                    "weights" to point.weights.toList(),
                    "expectedReturn" to point.expectedReturn,
                    "risk" to point.risk
                )
            },
            "lookbackDays" to lookbackDays
        )
    }

    /**
     * Robust Sharpe Optimization
     */
    @GetMapping("/robust-sharpe")
    fun getRobustSharpe(
        @RequestParam(defaultValue = "90") lookbackDays: Int,
        @RequestParam(defaultValue = "0.1") uncertaintyRadius: Double
    ): Map<String, Any> {
        val assets = tradingProperties.markets
        val result = robustOptimizer.robustSharpeOptimization(assets, lookbackDays, uncertaintyRadius)

        return success(
            "assets" to assets,
            "weights" to result.weights.toList(),
            "expectedReturn" to result.expectedReturn,
            "trueRisk" to result.trueRisk,
            "worstCaseRisk" to result.worstCaseRisk,
            "robustnessGap" to result.robustnessGap,
            "uncertaintyRadius" to result.uncertaintyRadius,
            "lookbackDays" to lookbackDays
        )
    }

    // ========== Execution Optimization APIs ==========

    /**
     * Market Impact 추정
     */
    @GetMapping("/market-impact")
    fun getMarketImpact(
        @RequestParam orderSize: Double,
        @RequestParam dailyVolume: Double,
        @RequestParam currentPrice: Double,
        @RequestParam(defaultValue = "true") isCrypto: Boolean,
        @RequestParam(defaultValue = "0.8") alpha: Double
    ): Map<String, Any> {
        val result = executionOptimizer.estimateMarketImpact(
            orderSize, dailyVolume, currentPrice, isCrypto, alpha
        )

        return success(
            "orderSize" to orderSize,
            "dailyVolume" to dailyVolume,
            "currentPrice" to currentPrice,
            "temporaryImpact" to result.temporaryImpact,
            "permanentImpact" to result.permanentImpact,
            "totalImpact" to result.totalImpact,
            "estimatedPrice" to result.estimatedPrice,
            "impactCost" to result.impactCost
        )
    }

    /**
     * TWAP 실행 슬라이스 계산
     */
    @GetMapping("/execution/twap")
    fun getTWAPSlices(
        @RequestParam totalQuantity: Double,
        @RequestParam(defaultValue = "60") executionWindow: Int,  // 분
        @RequestParam(defaultValue = "5") sliceInterval: Int
    ): Map<String, Any> {
        val slices = executionOptimizer.calculateTWAPSlices(
            totalQuantity, executionWindow, sliceInterval
        )

        return success(
            "totalQuantity" to totalQuantity,
            "executionWindow" to executionWindow,
            "sliceInterval" to sliceInterval,
            "slices" to slices.map { slice ->
                mapOf(
                    "sliceIndex" to slice.sliceIndex,
                    "quantity" to slice.quantity,
                    "targetTime" to slice.targetTime,
                    "cumulativeQuantity" to slice.cumulativeQuantity
                )
            }
        )
    }

    /**
     * 최적 실행 크기 계산
     */
    @GetMapping("/execution/optimal-size")
    fun getOptimalExecutionSize(
        @RequestParam targetValue: Double,
        @RequestParam currentPrice: Double,
        @RequestParam dailyVolume: Double,
        @RequestParam(defaultValue = "10") maxImpactBp: Double
    ): Map<String, Any> {
        val optimalSize = executionOptimizer.calculateOptimalExecutionSize(
            targetValue, currentPrice, dailyVolume, maxImpactBp
        )

        return success(
            "targetValue" to targetValue,
            "currentPrice" to currentPrice,
            "dailyVolume" to dailyVolume,
            "maxImpactBp" to maxImpactBp,
            "optimalSize" to optimalSize,
            "optimalValue" to optimalSize * currentPrice
        )
    }

    /**
     * 멀티데이 실행 스케줄
     */
    @GetMapping("/execution/multi-day-schedule")
    fun getMultiDayExecutionSchedule(
        @RequestParam totalQuantity: Double,
        @RequestParam dailyVolume: Double,
        @RequestParam(defaultValue = "0.05") maxDailyParticipationRate: Double
    ): Map<String, Any> {
        val schedule = executionOptimizer.calculateMultiDayExecutionSchedule(
            totalQuantity, dailyVolume, maxDailyParticipationRate
        )

        return success(
            "totalQuantity" to totalQuantity,
            "dailyVolume" to dailyVolume,
            "maxDailyParticipationRate" to maxDailyParticipationRate,
            "daysRequired" to schedule.size,
            "schedule" to schedule.mapIndexed { index, qty ->
                mapOf(
                    "day" to (index + 1),
                    "quantity" to qty
                )
            }
        )
    }

    private fun success(vararg entries: Pair<String, Any>): Map<String, Any> {
        return buildMap {
            put("success", true)
            entries.forEach { (key, value) -> put(key, value) }
        }
    }
}
