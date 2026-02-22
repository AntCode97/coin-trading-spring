import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ManualTraderWorkspace from './ManualTraderWorkspace';

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
      <ManualTraderWorkspace />
    </QueryClientProvider>
  );
}
