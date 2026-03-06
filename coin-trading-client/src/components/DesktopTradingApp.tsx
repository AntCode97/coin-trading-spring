import { useState, useCallback } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ManualTraderWorkspace from './ManualTraderWorkspace';
import AiDayTraderScreen from './ai-day-trader/AiDayTraderScreen';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

type Screen = 'workspace' | 'ai-day-trader';

export default function DesktopTradingApp() {
  const [screen, setScreen] = useState<Screen>('workspace');

  const goToAiTrader = useCallback(() => setScreen('ai-day-trader'), []);
  const goToWorkspace = useCallback(() => setScreen('workspace'), []);

  return (
    <QueryClientProvider client={queryClient}>
      {screen === 'workspace' && (
        <ManualTraderWorkspace onNavigateAiTrader={goToAiTrader} />
      )}
      {screen === 'ai-day-trader' && (
        <AiDayTraderScreen onBack={goToWorkspace} />
      )}
    </QueryClientProvider>
  );
}
