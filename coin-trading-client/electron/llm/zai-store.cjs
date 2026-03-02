const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const TOKEN_DIR = path.join(os.homedir(), '.coin-trading');
const ZAI_FILE = path.join(TOKEN_DIR, 'zai-api-key.json');

function ensureDir() {
  if (!fs.existsSync(TOKEN_DIR)) {
    fs.mkdirSync(TOKEN_DIR, { recursive: true, mode: 0o700 });
  }
}

function saveZaiApiKey(apiKey) {
  const normalized = typeof apiKey === 'string' ? apiKey.trim() : '';
  if (!normalized) {
    throw new Error('z.ai API Key가 비어 있습니다.');
  }

  ensureDir();
  const payload = {
    apiKey: normalized,
    updatedAt: new Date().toISOString(),
  };
  fs.writeFileSync(ZAI_FILE, JSON.stringify(payload, null, 2), { mode: 0o600 });
  fs.chmodSync(ZAI_FILE, 0o600);
  return { ok: true };
}

function loadZaiApiKey() {
  try {
    if (!fs.existsSync(ZAI_FILE)) return null;
    const raw = fs.readFileSync(ZAI_FILE, 'utf-8');
    const parsed = JSON.parse(raw);
    if (typeof parsed?.apiKey !== 'string' || !parsed.apiKey.trim()) return null;
    return {
      apiKey: parsed.apiKey.trim(),
      updatedAt: typeof parsed.updatedAt === 'string' ? parsed.updatedAt : null,
    };
  } catch {
    return null;
  }
}

function clearZaiApiKey() {
  try {
    if (fs.existsSync(ZAI_FILE)) {
      fs.unlinkSync(ZAI_FILE);
    }
  } catch {
    // ignore
  }
  return { ok: true };
}

module.exports = {
  saveZaiApiKey,
  loadZaiApiKey,
  clearZaiApiKey,
};
