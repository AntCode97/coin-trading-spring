const { randomBytes, createHash } = require('node:crypto');
const http = require('node:http');
const { URL } = require('node:url');

const OPENAI_AUTH_ENDPOINT = 'https://auth.openai.com/oauth/authorize';
const OPENAI_TOKEN_ENDPOINT = 'https://auth.openai.com/oauth/token';
const OPENAI_CLIENT_ID = 'app_EMoamEEZ73f0CkXaXp7hrann';
const OPENAI_SCOPES = 'openid profile email offline_access';
const CALLBACK_PORT = 1455;

function generatePkce() {
  const verifier = randomBytes(32).toString('base64url');
  const challenge = createHash('sha256').update(verifier).digest('base64url');
  return { verifier, challenge };
}

function cancelExistingServer() {
  return new Promise((resolve) => {
    const req = http.request(
      { hostname: '127.0.0.1', port: CALLBACK_PORT, path: '/cancel', method: 'GET', timeout: 2000 },
      (res) => { res.resume(); resolve(); }
    );
    req.on('error', () => resolve());
    req.on('timeout', () => { req.destroy(); resolve(); });
    req.end();
  });
}

function createCallbackServer() {
  return new Promise(async (resolve, reject) => {
    const server = http.createServer();
    server.on('error', async (err) => {
      if (err.code === 'EADDRINUSE') {
        await cancelExistingServer();
        await new Promise((r) => setTimeout(r, 500));
        server.listen(CALLBACK_PORT, '127.0.0.1');
      } else {
        reject(err);
      }
    });
    server.listen(CALLBACK_PORT, '127.0.0.1', () => {
      resolve({ server, port: CALLBACK_PORT });
    });
  });
}

function waitForCallback(server, expectedState, timeoutMs = 5 * 60 * 1000) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      server.close();
      reject(new Error('OAuth 콜백 대기 시간(5분)이 초과되었습니다.'));
    }, timeoutMs);

    server.on('request', (req, res) => {
      const url = new URL(req.url, 'http://localhost');

      if (url.pathname === '/cancel') {
        res.writeHead(200);
        res.end('cancelled');
        clearTimeout(timeout);
        server.close();
        reject(new Error('이전 OAuth 세션이 취소되었습니다.'));
        return;
      }

      if (url.pathname !== '/auth/callback') {
        res.writeHead(404);
        res.end('Not found');
        return;
      }

      const code = url.searchParams.get('code');
      const state = url.searchParams.get('state');
      const error = url.searchParams.get('error');

      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      if (error) {
        res.end('<html><body><h2>인증 실패</h2><p>브라우저를 닫아도 됩니다.</p></body></html>');
      } else {
        res.end('<html><body><h2>인증 완료</h2><p>브라우저를 닫고 앱으로 돌아가세요.</p></body></html>');
      }

      clearTimeout(timeout);
      server.close();

      if (error) {
        reject(new Error(`OAuth 인증 실패: ${error}`));
        return;
      }
      if (state !== expectedState) {
        reject(new Error('OAuth state 불일치 (CSRF 방지 검증 실패)'));
        return;
      }
      resolve(code);
    });
  });
}

async function exchangeCodeForToken(code, codeVerifier, redirectUri) {
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    code,
    redirect_uri: redirectUri,
    client_id: OPENAI_CLIENT_ID,
    code_verifier: codeVerifier,
  });

  const res = await fetch(OPENAI_TOKEN_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
  });

  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`토큰 교환 실패 (${res.status}): ${text}`);
  }

  return await res.json();
}

async function refreshAccessToken(refreshTokenValue) {
  const body = new URLSearchParams({
    grant_type: 'refresh_token',
    refresh_token: refreshTokenValue,
    client_id: OPENAI_CLIENT_ID,
  });

  const res = await fetch(OPENAI_TOKEN_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString(),
  });

  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`토큰 갱신 실패 (${res.status}): ${text}`);
  }

  return await res.json();
}

async function runOAuthFlow(openExternalFn) {
  const { verifier, challenge } = generatePkce();
  const state = randomBytes(16).toString('base64url');

  const { server, port } = await createCallbackServer();
  const redirectUri = `http://localhost:${port}/auth/callback`;

  const authUrl = `${OPENAI_AUTH_ENDPOINT}?` + new URLSearchParams({
    response_type: 'code',
    client_id: OPENAI_CLIENT_ID,
    redirect_uri: redirectUri,
    scope: OPENAI_SCOPES,
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
    codex_cli_simplified_flow: 'true',
    originator: 'coin-trading-desktop',
  }).toString();

  await openExternalFn(authUrl);

  const code = await waitForCallback(server, state);
  const tokenData = await exchangeCodeForToken(code, verifier, redirectUri);
  return tokenData;
}

module.exports = { runOAuthFlow, refreshAccessToken };
