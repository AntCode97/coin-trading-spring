import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { dashboardApi } from '../api';

export default function Dashboard() {
  const [daysAgo, setDaysAgo] = useState(0);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['dashboard', daysAgo],
    queryFn: () => dashboardApi.getData(daysAgo),
    refetchInterval: 30000, // 30초마다 자동 갱신
  });

  const handleDateChange = (offset: number) => {
    const newValue = Math.max(0, Math.min(7, daysAgo + offset));
    setDaysAgo(newValue);
  };

  const handleManualSell = async (market: string, strategy: string) => {
    if (!confirm(`${market} (${strategy}) 포지션을 매도하시겠습니까?`)) {
      return;
    }

    try {
      const result = await dashboardApi.manualClose(market, strategy);
      if (result.success) {
        alert('매도 완료!');
        refetch();
      } else {
        alert('매도 실패: ' + (result.error || '알 수 없는 오류'));
      }
    } catch (e: any) {
      alert('매도 오류: ' + e.message);
    }
  };

  if (isLoading) {
    return (
      <div className="min-h-screen bg-dark-900 flex items-center justify-center">
        <div className="text-gray-400">로딩 중...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="min-h-screen bg-dark-900 flex items-center justify-center">
        <div className="text-red-400">
          오류가 발생했습니다: {error instanceof Error ? error.message : '알 수 없는 오류'}
        </div>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="min-h-screen bg-dark-900 flex items-center justify-center">
        <div className="text-gray-400">데이터를 불러올 수 없습니다</div>
      </div>
    );
  }

  const exitReasonTitles: Record<string, string> = {
    TAKE_PROFIT: '목표 익절가 도달',
    STOP_LOSS: '손절가 도달',
    TIMEOUT: '보유 시간 초과',
    TRAILING_STOP: '트레일링 스탑 청산',
    ABANDONED_NO_BALANCE: '잔고 부족 (매수 안됨)',
    SIGNAL_REVERSAL: '반대 신호 발생',
    MANUAL: '수동 매도',
    UNKNOWN: '기타 사유',
  };

  return (
    <div className="min-h-screen bg-dark-900 text-gray-100 p-4 md:p-8">
      <div className="max-w-7xl mx-auto">
        {/* 헤더 */}
        <div className="mb-8">
          <h1 className="text-3xl font-bold text-white mb-2">Coin Trading Dashboard</h1>
          <p className="text-gray-400">
            총 자산: <span className="text-xl font-semibold text-green-400">
              {data.totalAssetKrw.toLocaleString()}원
            </span>
            <span className="ml-4 text-gray-500">
              (KRW: {data.krwBalance.toLocaleString()}원)
            </span>
          </p>
        </div>

        {/* 열린 포지션 */}
        {data.openPositions.length > 0 && (
          <section className="mb-8">
            <h2 className="text-xl font-bold mb-4 text-white">열린 포지션</h2>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-dark-800">
                  <tr>
                    <th className="px-4 py-3 text-left">마켓</th>
                    <th className="px-4 py-3 text-left">전략</th>
                    <th className="px-4 py-3 text-right">진입가</th>
                    <th className="px-4 py-3 text-right">현재가</th>
                    <th className="px-4 py-3 text-right">수량</th>
                    <th className="px-4 py-3 text-right">평가금액</th>
                    <th className="px-4 py-3 text-right">손익</th>
                    <th className="px-4 py-3 text-right">손익률</th>
                    <th className="px-4 py-3 text-right">익절가</th>
                    <th className="px-4 py-3 text-right">손절가</th>
                    <th className="px-4 py-3 text-center">매도</th>
                  </tr>
                </thead>
                <tbody>
                  {data.openPositions.map((pos, idx) => (
                    <tr key={idx} className="border-t border-dark-700 hover:bg-dark-800">
                      <td className="px-4 py-3">{pos.market}</td>
                      <td className="px-4 py-3">
                        <span className={`px-2 py-1 rounded text-xs ${
                          pos.strategy === 'Meme Scalper' ? 'bg-purple-600' :
                          pos.strategy === 'Volume Surge' ? 'bg-blue-600' : 'bg-green-600'
                        }`}>
                          {pos.strategy}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-right">{pos.entryPrice.toLocaleString()}</td>
                      <td className="px-4 py-3 text-right">{pos.currentPrice.toLocaleString()}</td>
                      <td className="px-4 py-3 text-right">{pos.quantity.toFixed(4)}</td>
                      <td className="px-4 py-3 text-right">{pos.value.toLocaleString()}원</td>
                      <td className={`px-4 py-3 text-right ${pos.pnl >= 0 ? 'text-green-400' : 'text-red-400'}`}>
                        {pos.pnl >= 0 ? '+' : ''}{pos.pnl.toLocaleString()}원
                      </td>
                      <td className={`px-4 py-3 text-right ${pos.pnlPercent >= 0 ? 'text-green-400' : 'text-red-400'}`}>
                        {pos.pnlPercent >= 0 ? '+' : ''}{pos.pnlPercent.toFixed(2)}%
                      </td>
                      <td className="px-4 py-3 text-right">{pos.takeProfitPrice.toLocaleString()}</td>
                      <td className="px-4 py-3 text-right">{pos.stopLossPrice.toLocaleString()}</td>
                      <td className="px-4 py-3 text-center">
                        <button
                          onClick={() => handleManualSell(pos.market, pos.strategy)}
                          className="px-3 py-1 bg-red-600 hover:bg-red-700 rounded text-sm"
                        >
                          매도
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        )}

        {/* 거래 내역 */}
        <section className="mb-8">
          <div className="flex justify-between items-center mb-4">
            <h2 className="text-xl font-bold text-white">거래 내역</h2>
            <div className="flex items-center gap-3">
              <button
                onClick={() => handleDateChange(-1)}
                disabled={daysAgo >= 7}
                className="px-3 py-1 bg-dark-700 hover:bg-dark-600 disabled:opacity-30 disabled:cursor-not-allowed rounded text-sm"
              >
                ◀ 이전
              </button>
              <span className="text-sm min-w-[100px] text-center">{data.currentDateStr}</span>
              <button
                onClick={() => handleDateChange(1)}
                disabled={daysAgo <= 0}
                className="px-3 py-1 bg-dark-700 hover:bg-dark-600 disabled:opacity-30 disabled:cursor-not-allowed rounded text-sm"
              >
                다음 ▶
              </button>
            </div>
          </div>

          {data.todayTrades.length === 0 ? (
            <div className="text-center py-8 text-gray-500">해당 날짜에 체결된 거래가 없습니다</div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="bg-dark-800">
                  <tr>
                    <th className="px-4 py-3 text-left">마켓</th>
                    <th className="px-4 py-3 text-left">전략</th>
                    <th className="px-4 py-3 text-right">진입가</th>
                    <th className="px-4 py-3 text-right">청산가</th>
                    <th className="px-4 py-3 text-right">수량</th>
                    <th className="px-4 py-3 text-right">보유시간</th>
                    <th className="px-4 py-3 text-right">손익</th>
                    <th className="px-4 py-3 text-right">손익률</th>
                    <th className="px-4 py-3 text-left">사유</th>
                  </tr>
                </thead>
                <tbody>
                  {data.todayTrades.map((trade, idx) => (
                    <tr key={idx} className="border-t border-dark-700 hover:bg-dark-800">
                      <td className="px-4 py-3">{trade.market}</td>
                      <td className="px-4 py-3">
                        <span className={`px-2 py-1 rounded text-xs ${
                          trade.strategy === 'Meme Scalper' ? 'bg-purple-600' :
                          trade.strategy === 'Volume Surge' ? 'bg-blue-600' : 'bg-green-600'
                        }`}>
                          {trade.strategy}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-right">{trade.entryPrice.toLocaleString()}</td>
                      <td className="px-4 py-3 text-right">{trade.exitPrice.toLocaleString()}</td>
                      <td className="px-4 py-3 text-right">{trade.quantity.toFixed(4)}</td>
                      <td className="px-4 py-3 text-right" title={`${trade.entryTimeFormatted} ~ ${trade.exitTimeFormatted}`}>
                        {trade.holdingMinutes}분
                      </td>
                      <td className={`px-4 py-3 text-right ${trade.pnlAmount >= 0 ? 'text-green-400' : 'text-red-400'}`}>
                        {trade.pnlAmount >= 0 ? '+' : ''}{trade.pnlAmount.toLocaleString()}원
                      </td>
                      <td className={`px-4 py-3 text-right ${trade.pnlPercent >= 0 ? 'text-green-400' : 'text-red-400'}`}>
                        {trade.pnlPercent >= 0 ? '+' : ''}{trade.pnlPercent.toFixed(2)}%
                      </td>
                      <td className="px-4 py-3">
                        <span
                          className={`px-2 py-1 rounded text-xs ${
                            trade.exitReason === 'TAKE_PROFIT' ? 'bg-green-600' :
                            trade.exitReason === 'STOP_LOSS' ? 'bg-red-600' : 'bg-gray-600'
                          }`}
                          title={exitReasonTitles[trade.exitReason] || '기타 사유'}
                        >
                          {trade.exitReason}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>

        {/* 오늘 통계 */}
        <section className="mb-8">
          <h2 className="text-xl font-bold mb-4 text-white">오늘 통계</h2>
          <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
            <div className="card">
              <div className="text-gray-400 text-sm">총 거래</div>
              <div className="text-2xl font-bold">{data.todayStats.totalTrades}</div>
            </div>
            <div className="card">
              <div className="text-gray-400 text-sm">승리</div>
              <div className="text-2xl font-bold text-green-400">{data.todayStats.winCount}</div>
            </div>
            <div className="card">
              <div className="text-gray-400 text-sm">패배</div>
              <div className="text-2xl font-bold text-red-400">{data.todayStats.lossCount}</div>
            </div>
            <div className="card">
              <div className="text-gray-400 text-sm">승률</div>
              <div className="text-2xl font-bold">{(data.todayStats.winRate * 100).toFixed(1)}%</div>
            </div>
            <div className="card">
              <div className="text-gray-400 text-sm">총 손익</div>
              <div className={`text-2xl font-bold ${data.todayStats.totalPnl >= 0 ? 'text-green-400' : 'text-red-400'}`}>
                {data.todayStats.totalPnl >= 0 ? '+' : ''}{data.todayStats.totalPnl.toLocaleString()}원
              </div>
            </div>
          </div>
        </section>
      </div>
    </div>
  );
}
