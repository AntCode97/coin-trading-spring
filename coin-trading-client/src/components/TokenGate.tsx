import { useState } from 'react';
import { setWebToken, clearWebToken } from '../lib/authToken';
import { getApiBaseUrl } from '../api';
import './TokenGate.css';

interface TokenGateProps {
  onAuthenticated: () => void;
}

export default function TokenGate({ onAuthenticated }: TokenGateProps) {
  const [token, setToken] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = token.trim();
    if (!trimmed) return;

    setLoading(true);
    setError('');

    try {
      setWebToken(trimmed);
      const res = await fetch(`${getApiBaseUrl()}/guided-trading/verify-token`, {
        headers: { 'X-Desktop-Token': trimmed },
      });
      if (res.ok) {
        onAuthenticated();
      } else {
        clearWebToken();
        setError(res.status === 401 ? '유효하지 않은 토큰입니다.' : `인증 실패 (${res.status})`);
      }
    } catch {
      clearWebToken();
      setError('서버에 연결할 수 없습니다.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="token-gate">
      <form className="token-gate__card" onSubmit={handleSubmit}>
        <h2 className="token-gate__title">Trading Workspace</h2>
        <p className="token-gate__desc">접근 토큰을 입력하세요.</p>
        <input
          className="token-gate__input"
          type="password"
          placeholder="DESKTOP_TRADING_TOKEN"
          value={token}
          onChange={(e) => setToken(e.target.value)}
          autoFocus
        />
        <button className="token-gate__submit" type="submit" disabled={loading || !token.trim()}>
          {loading ? '확인 중...' : '접속'}
        </button>
        {error && <div className="token-gate__error">{error}</div>}
      </form>
    </div>
  );
}
