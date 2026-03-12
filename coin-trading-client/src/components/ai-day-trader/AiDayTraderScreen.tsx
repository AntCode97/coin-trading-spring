import {
  AiDayTraderConfigPanel,
  AiDayTraderHistoryPanel,
  AiDayTraderJournalPanel,
  AiDayTraderPositionsPanel,
  AiDayTraderQueuePanel,
  AiDayTraderReviewAgentPanel,
  AiDayTraderSessionBar,
  AiDayTraderStrategyPanel,
  AiDayTraderWatchlistPanel,
} from './AiDayTraderScreenSections';
import AiDayTraderPixelMonitor from './AiDayTraderPixelMonitor';
import { useAiDayTraderScreen } from './useAiDayTraderScreen';
import './AiDayTraderScreen.css';

export default function AiDayTraderScreen() {
  const screen = useAiDayTraderScreen();

  return (
    <div className="ai-scalp-terminal">
      <AiDayTraderSessionBar
        config={screen.config}
        state={screen.state}
        session={screen.session}
        provider={screen.provider}
        onEngineAction={() => void screen.handleEngineAction()}
        onToggleMonitor={screen.toggleMonitor}
        isMonitorOpen={screen.isMonitorOpen}
      />

      <div className="ai-scalp-layout">
        <AiDayTraderQueuePanel opportunities={screen.state.queue} />

        <AiDayTraderJournalPanel
          events={screen.state.events}
          journal={screen.journal}
          journalScrollRef={screen.journalScrollRef}
          onLatest={screen.goToLatestJournalPage}
          onPrevious={screen.goToPreviousJournalPage}
          onNext={screen.goToNextJournalPage}
        />

        <aside className="ai-scalp-side">
          <AiDayTraderWatchlistPanel
            config={screen.config}
            watchlist={screen.watchlist}
            toggleSelectedMarket={screen.toggleSelectedMarket}
            clearSelectedMarkets={screen.clearSelectedMarkets}
            setSeedMarket={screen.setSeedMarket}
            setSeedOrderType={screen.setSeedOrderType}
            setSeedLimitPrice={screen.setSeedLimitPrice}
            setSeedStopLossPrice={screen.setSeedStopLossPrice}
            setSeedTakeProfitPrice={screen.setSeedTakeProfitPrice}
            setSeedMaxDcaCount={screen.setSeedMaxDcaCount}
            setSeedDcaStepPercent={screen.setSeedDcaStepPercent}
            startSeedPosition={() => void screen.startSeedPosition()}
          />

          <AiDayTraderPositionsPanel
            positions={screen.state.positions}
            scanCycles={screen.state.scanCycles}
            finalistsReviewed={screen.state.finalistsReviewed}
            buyExecutions={screen.state.buyExecutions}
          />

          <AiDayTraderStrategyPanel
            reflection={screen.state.strategyReflection}
          />

          <AiDayTraderReviewAgentPanel
            review={screen.review}
          />

          <AiDayTraderConfigPanel
            config={screen.config}
            running={screen.session.running}
            provider={screen.provider}
            updateConfig={screen.updateConfig}
            onRefreshConnection={() => void screen.refreshConnectionStatus()}
            onOpenAiLogin={() => void screen.handleOpenAiLogin()}
            onOpenAiLogout={() => void screen.handleOpenAiLogout()}
            onSaveZaiKey={() => void screen.handleSaveZaiKey()}
            onClearZaiKey={() => void screen.handleClearZaiKey()}
            onZaiApiKeyChange={screen.setZaiApiKeyInput}
          />
        </aside>
      </div>

      <AiDayTraderHistoryPanel
        history={screen.history}
        onSelectMarket={screen.setSelectedHistoryMarket}
      />

      <AiDayTraderPixelMonitor
        open={screen.isMonitorOpen}
        state={screen.state}
        todayTrades={screen.history.todayTrades}
        config={screen.config}
        providerConnected={screen.provider.providerConnected}
        onClose={screen.closeMonitor}
        onFocusActor={screen.setMonitorFocus}
      />
    </div>
  );
}
