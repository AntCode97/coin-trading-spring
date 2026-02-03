package com.ant.cointrading.portfolio

import com.ant.cointrading.repository.OhlcvHistoryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Hierarchical Risk Parity (HRP) Allocator
 *
 * 상관관계 구조를 고려한 계층적 포트폴리오 최적화
 */
@Service
class HRPAllocator(
    private val ohlcvHistoryRepository: OhlcvHistoryRepository
) {
    private val log = LoggerFactory.getLogger(HRPAllocator::class.java)

    companion object {
        private const val MIN_HISTORY_DAYS = 30
    }

    /**
     * HRP 기반 가중치 계산
     *
     * @param assets 자산 목록
     * @param lookbackDays 과거 데이터 기간
     * @return HRP 가중치
     */
    fun calculateHRPWeights(
        assets: List<String>,
        lookbackDays: Int = 90
    ): HRPResult {
        if (assets.size < 2) {
            return HRPResult(
                weights = doubleArrayOf(1.0),
                clusters = listOf(listOf(0)),
                linkageDistance = 0.0
            )
        }

        val cutoffTime = Instant.now().minus(java.time.Duration.ofDays(lookbackDays.toLong()))

        // 상관관계 행렬 계산
        val correlationMatrix = calculateCorrelationMatrix(assets, cutoffTime)
            ?: return getEqualWeights(assets)

        // 거리 행렬 계산
        val distanceMatrix = calculateDistanceMatrix(correlationMatrix)

        // 계층적 군집화
        val clusters = hierarchicalClustering(distanceMatrix)

        // HRP 가중치 배정
        val weights = assignHRPWeights(clusters, assets, cutoffTime)

        // 군집 내 평균 거리
        val avgDistance = calculateAverageLinkageDistance(distanceMatrix)

        log.info("HRP 완료: ${assets.size}개 자산, ${clusters.size}개 클러스터")

        return HRPResult(
            weights = weights,
            clusters = clusters,
            linkageDistance = avgDistance
        )
    }

    /**
     * Clustered Risk Parity (CRP)
     *
     * HRP + K-Means 군집화 조합
     */
    fun calculateClusteredRiskParity(
        assets: List<String>,
        lookbackDays: Int = 90,
        nClusters: Int = 3
   ): HRPResult {
        if (assets.size < 2 || nClusters < 2) {
            return calculateHRPWeights(assets, lookbackDays)
        }

        val cutoffTime = Instant.now().minus(java.time.Duration.ofDays(lookbackDays.toLong()))
        val correlationMatrix = calculateCorrelationMatrix(assets, cutoffTime)
            ?: return getEqualWeights(assets)

        // K-Means 군집화 (상관관계 기반)
        val clusters = kMeansClustering(correlationMatrix, nClusters, assets)

        // 공분산 행렬 계산
        val returnsMatrix = calculateReturnsMatrix(assets, cutoffTime)
        val covMatrix = if (returnsMatrix != null) {
            calculateCovarianceMatrix(returnsMatrix)
        } else {
            Array(assets.size) { i ->
                DoubleArray(assets.size) { j -> if (i == j) 1.0 else 0.0 }
            }
        }

        // 클러스터 간 Risk Parity
        val clusterVariances = clusters.map { cluster ->
            calculateClusterVariance(cluster, covMatrix)
        }
        val totalVariance = clusterVariances.sum()
        val clusterWeights = clusterVariances.map { it / totalVariance }.toDoubleArray()

        // 최종 가중치
        val finalWeights = DoubleArray(assets.size)
        clusters.forEachIndexed { clusterIdx, cluster ->
            val clusterWeight = clusterWeights[clusterIdx]
            val intraClusterWeight = 1.0 / cluster.size
            cluster.forEach { assetIdx ->
                finalWeights[assetIdx] = clusterWeight * intraClusterWeight
            }
        }

        // 정규화
        val sumW = finalWeights.sum()
        val normalizedWeights = if (sumW > 0) {
            finalWeights.map { it / sumW }.toDoubleArray()
        } else {
            DoubleArray(assets.size) { 1.0 / assets.size }
        }

        log.info("CRP 완료: ${clusters.size}개 클러스터")

        return HRPResult(
            weights = normalizedWeights,
            clusters = clusters,
            linkageDistance = 0.0
        )
    }

    /**
     * 상관관계 행렬 계산
     */
    private fun calculateCorrelationMatrix(
        assets: List<String>,
        cutoffTime: Instant
    ): Array<DoubleArray>? {
        val returnsMatrix = calculateReturnsMatrix(assets, cutoffTime) ?: return null
        val nAssets = assets.size

        // 표준편차 계산
        val stdDevs = DoubleArray(nAssets) { i ->
            val mean = returnsMatrix.map { it[i] }.average()
            sqrt(returnsMatrix.map { (it[i] - mean).let { d -> d * d } }.average())
        }

        // 상관관계 행렬
        val correlationMatrix = Array(nAssets) { i ->
            DoubleArray(nAssets) { j ->
                if (i == j) {
                    1.0
                } else {
                    val meanI = returnsMatrix.map { it[i] }.average()
                    val meanJ = returnsMatrix.map { it[j] }.average()

                    var covariance = 0.0
                    for (t in returnsMatrix.indices) {
                        covariance += (returnsMatrix[t][i] - meanI) * (returnsMatrix[t][j] - meanJ)
                    }
                    covariance /= returnsMatrix.size

                    val correlation = if (stdDevs[i] > 0 && stdDevs[j] > 0) {
                        covariance / (stdDevs[i] * stdDevs[j])
                    } else {
                        0.0
                    }

                    correlation.coerceIn(-1.0, 1.0)
                }
            }
        }

        return correlationMatrix
    }

    /**
     * 거리 행렬 계산 (상관관계 기반)
     *
     * d(i,j) = sqrt(0.5 * (1 - corr(i,j)))
     */
    private fun calculateDistanceMatrix(correlationMatrix: Array<DoubleArray>): Array<DoubleArray> {
        val n = correlationMatrix.size
        return Array(n) { i ->
            DoubleArray(n) { j ->
                sqrt(0.5 * (1 - correlationMatrix[i][j]))
            }
        }
    }

    /**
     * 계층적 군집화 (Single Linkage)
     */
    private fun hierarchicalClustering(
        distanceMatrix: Array<DoubleArray>
    ): List<List<Int>> {
        val n = distanceMatrix.size
        val clusters = (0 until n).map { listOf(it) }.toMutableList()

        while (clusters.size > 1) {
            var minDistance = Double.MAX_VALUE
            var mergePair = Pair(0, 0)

            // 가장 가까운 클러스터 쌍 찾기
            for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    val dist = clusterDistance(clusters[i], clusters[j], distanceMatrix)
                    if (dist < minDistance) {
                        minDistance = dist
                        mergePair = Pair(i, j)
                    }
                }
            }

            // 병합
            val merged = clusters[mergePair.first] + clusters[mergePair.second]

            // 인덱스 조정 (역순으로 삭제)
            clusters.removeAt(mergePair.second)
            clusters.removeAt(mergePair.first)
            clusters.add(merged)
        }

        return clusters
    }

    /**
     * K-Means 군집화 (간단 구현)
     */
    private fun kMeansClustering(
        correlationMatrix: Array<DoubleArray>,
        nClusters: Int,
        assets: List<String>
    ): List<List<Int>> {
        val n = assets.size
        if (n <= nClusters) {
            return (0 until n).map { listOf(it) }
        }

        // 초기 중심점 무작위 선택
        val centroids = (0 until n).shuffled().take(nClusters).toIntArray()
        val clusters = Array(nClusters) { mutableListOf<Int>() }

        // 각 자산을 가장 가까운 중심점에 할당
        for (i in 0 until n) {
            var minDist = Double.MAX_VALUE
            var closestCentroid = 0

            for (c in 0 until nClusters) {
                val dist = abs(correlationMatrix[i][centroids[c]] - 1.0)
                if (dist < minDist) {
                    minDist = dist
                    closestCentroid = c
                }
            }

            clusters[closestCentroid].add(i)
        }

        return clusters.filter { it.isNotEmpty() }.map { it.toList() }
    }

    /**
     * 클러스터 간 거리 (Single Linkage)
     */
    private fun clusterDistance(
        cluster1: List<Int>,
        cluster2: List<Int>,
        distanceMatrix: Array<DoubleArray>
    ): Double {
        var minDist = Double.MAX_VALUE

        for (i in cluster1) {
            for (j in cluster2) {
                if (distanceMatrix[i][j] < minDist) {
                    minDist = distanceMatrix[i][j]
                }
            }
        }

        return minDist
    }

    /**
     * HRP 가중치 배정 (반분할 방식)
     */
    private fun assignHRPWeights(
        clusters: List<List<Int>>,
        assets: List<String>,
        cutoffTime: Instant
    ): DoubleArray {
        val n = assets.size
        val weights = DoubleArray(n)

        // 군집이 하나면 균등 분배
        if (clusters.size == 1) {
            return DoubleArray(n) { 1.0 / n }
        }

        val clusterItems = clusters.flatten()

        // 공분산 행렬
        val returnsMatrix = calculateReturnsMatrix(assets, cutoffTime)
        val covMatrix = if (returnsMatrix != null) {
            calculateCovarianceMatrix(returnsMatrix)
        } else {
            Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        }

        // 반분할 가중치 배정
        fun assignRecursive(cluster: List<Int>, weight: Double) {
            if (cluster.size == 1) {
                weights[cluster[0]] = weight
            } else {
                val mid = cluster.size / 2
                val left = cluster.take(mid)
                val right = cluster.drop(mid)

                // 역분산 비율
                val leftVar = calculateClusterVariance(left, covMatrix)
                val rightVar = calculateClusterVariance(right, covMatrix)
                val totalVar = leftVar + rightVar

                assignRecursive(left, weight * rightVar / totalVar)
                assignRecursive(right, weight * leftVar / totalVar)
            }
        }

        assignRecursive(clusterItems, 1.0)
        return weights
    }

    /**
     * 클러스터 분산 계산
     */
    private fun calculateClusterVariance(
        cluster: List<Int>,
        covMatrix: Array<DoubleArray>
    ): Double {
        if (cluster.isEmpty()) return 1.0

        // 클러스터 내 분산의 합 (동일 가중치 가정)
        return cluster.sumOf { i ->
            cluster.sumOf { j -> covMatrix[i][j] }
        } / (cluster.size * cluster.size)
    }

    /**
     * 평균 링키지 거리 계산
     */
    private fun calculateAverageLinkageDistance(distanceMatrix: Array<DoubleArray>): Double {
        val n = distanceMatrix.size
        var sum = 0.0
        var count = 0

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                sum += distanceMatrix[i][j]
                count++
            }
        }

        return if (count > 0) sum / count else 0.0
    }

    /**
     * 수익률 행렬 계산
     */
    private fun calculateReturnsMatrix(
        assets: List<String>,
        cutoffTime: Instant
    ): Array<DoubleArray>? {
        val interval = "1d"
        val cutoffTimestamp = cutoffTime.toEpochMilli() / 1000

        val histories = assets.associateWith { asset ->
            ohlcvHistoryRepository.findByMarketAndIntervalOrderByTimestampAsc(asset, interval)
                .filter { it.timestamp >= cutoffTimestamp }
        }

        val minCount = histories.values.minOfOrNull { it.size } ?: return null
        if (minCount < MIN_HISTORY_DAYS) {
            log.warn("충분한 historical data 없음: $minCount < $MIN_HISTORY_DAYS")
            return null
        }

        val prices = histories.mapValues { (_, history) ->
            history.take(minCount).map { it.close }
        }

        val returns = prices.mapValues { (_, assetPrices) ->
            assetPrices.zipWithNext().map { (prev, curr) ->
                if (prev > 0) (curr - prev) / prev else 0.0
            }
        }

        val numReturns = returns.values.first().size
        return Array(numReturns) { t ->
            DoubleArray(assets.size) { a ->
                returns[assets[a]]?.get(t) ?: 0.0
            }
        }
    }

    /**
     * 공분산 행렬 계산
     */
    private fun calculateCovarianceMatrix(returnsMatrix: Array<DoubleArray>): Array<DoubleArray> {
        val nAssets = returnsMatrix[0].size
        val means = DoubleArray(nAssets) { i ->
            returnsMatrix.map { row -> row[i] }.average()
        }

        return Array(nAssets) { i ->
            DoubleArray(nAssets) { j ->
                val n = returnsMatrix.size
                var cov = 0.0
                for (t in 0 until n) {
                    cov += (returnsMatrix[t][i] - means[i]) * (returnsMatrix[t][j] - means[j])
                }
                cov / (n - 1)
            }
        }
    }

    /**
     * 균등 가중치 (Fallback)
     */
    private fun getEqualWeights(assets: List<String>): HRPResult {
        val n = assets.size
        return HRPResult(
            weights = DoubleArray(n) { 1.0 / n },
            clusters = (0 until n).map { listOf(it) },
            linkageDistance = 0.0
        )
    }
}

/**
 * HRP 결과
 */
data class HRPResult(
    val weights: DoubleArray,              // 자산별 가중치
    val clusters: List<List<Int>>,         // 군집 결과
    val linkageDistance: Double            // 평균 링키지 거리
)
