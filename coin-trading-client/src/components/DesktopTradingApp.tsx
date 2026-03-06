import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AiDayTraderScreen from './ai-day-trader/AiDayTraderScreen';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

export default function DesktopTradingApp() {
  return (
    <QueryClientProvider client={queryClient}>
      <AiDayTraderScreen />
    </QueryClientProvider>
  );
}
