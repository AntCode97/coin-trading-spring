import { lazy, Suspense, useCallback, useEffect, useState } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Dashboard from './components/Dashboard';
import DesktopTradingApp from './components/DesktopTradingApp';
import TokenGate from './components/TokenGate';
import { hasWebToken, clearWebToken } from './lib/authToken';
import './App.css';

const ManualTraderWorkspace = lazy(() => import('./components/ManualTraderWorkspace'));

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

type WebTab = 'dashboard' | 'trading';

function App() {
  const isDesktopMode = import.meta.env.VITE_DESKTOP_MODE === 'true';

  if (isDesktopMode) {
    return <DesktopTradingApp />;
  }

  return (
    <QueryClientProvider client={queryClient}>
      <WebApp />
    </QueryClientProvider>
  );
}

function WebApp() {
  const [activeTab, setActiveTab] = useState<WebTab>('dashboard');
  const [authenticated, setAuthenticated] = useState(hasWebToken);

  const handleAuthExpired = useCallback(() => {
    setAuthenticated(false);
    setActiveTab('dashboard');
  }, []);

  useEffect(() => {
    window.addEventListener('web-auth-expired', handleAuthExpired);
    return () => window.removeEventListener('web-auth-expired', handleAuthExpired);
  }, [handleAuthExpired]);

  const handleLock = () => {
    clearWebToken();
    setAuthenticated(false);
    setActiveTab('dashboard');
  };

  return (
    <>
      <nav className="web-tab-bar">
        <button
          className={`web-tab-bar__btn${activeTab === 'dashboard' ? ' web-tab-bar__btn--active' : ''}`}
          onClick={() => setActiveTab('dashboard')}
        >
          Dashboard
        </button>
        <button
          className={`web-tab-bar__btn${activeTab === 'trading' ? ' web-tab-bar__btn--active' : ''}`}
          onClick={() => setActiveTab('trading')}
        >
          Trading
        </button>
        {authenticated && (
          <button className="web-tab-bar__lock" onClick={handleLock}>
            Lock
          </button>
        )}
      </nav>

      {activeTab === 'dashboard' && <Dashboard />}
      {activeTab === 'trading' && (
        authenticated ? (
          <Suspense fallback={<div className="web-loading">Loading...</div>}>
            <ManualTraderWorkspace />
          </Suspense>
        ) : (
          <TokenGate onAuthenticated={() => setAuthenticated(true)} />
        )
      )}
    </>
  );
}

export default App;
