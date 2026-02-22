const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const TOKEN_DIR = path.join(os.homedir(), '.coin-trading');
const TOKEN_FILE = path.join(TOKEN_DIR, 'oauth-tokens.json');

function extractAccountId(accessToken) {
  try {
    const parts = (accessToken || '').split('.');
    if (parts.length < 2) return null;
    const payload = JSON.parse(Buffer.from(parts[1], 'base64').toString('utf-8'));
    return payload['https://api.openai.com/auth']?.account_id
      ?? payload.account_id
      ?? payload.sub
      ?? null;
  } catch {
    return null;
  }
}

function saveToken(tokenData) {
  if (!fs.existsSync(TOKEN_DIR)) {
    fs.mkdirSync(TOKEN_DIR, { recursive: true, mode: 0o700 });
  }
  const accessToken = tokenData.access_token ?? tokenData.accessToken;
  const payload = {
    accessToken,
    refreshToken: tokenData.refresh_token ?? tokenData.refreshToken,
    accountId: tokenData.accountId ?? extractAccountId(accessToken),
    expiresAt: tokenData.expiresAt ?? (tokenData.expires_in
      ? Math.floor(Date.now() / 1000) + tokenData.expires_in
      : null),
  };
  fs.writeFileSync(TOKEN_FILE, JSON.stringify(payload, null, 2), { mode: 0o600 });
  return payload;
}

function loadToken() {
  try {
    if (!fs.existsSync(TOKEN_FILE)) return null;
    const raw = fs.readFileSync(TOKEN_FILE, 'utf-8');
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

function deleteToken() {
  try {
    if (fs.existsSync(TOKEN_FILE)) fs.unlinkSync(TOKEN_FILE);
  } catch {
    // ignore
  }
}

function isTokenExpired(tokenData) {
  if (!tokenData || !tokenData.expiresAt) return false;
  return Date.now() / 1000 >= tokenData.expiresAt - 300;
}

module.exports = { saveToken, loadToken, deleteToken, isTokenExpired };
