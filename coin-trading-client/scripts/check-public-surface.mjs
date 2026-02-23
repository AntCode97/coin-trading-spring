import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const root = process.cwd();

function read(relativePath) {
  return readFileSync(resolve(root, relativePath), 'utf8');
}

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

const appTsx = read('src/App.tsx');
const dashboardTsx = read('src/components/Dashboard.tsx');

// 정적 import만 차단, 동적 import()는 허용 (토큰 게이트로 보호)
const hasStaticImport = /^import\s+\w+\s+from\s+['"]\.\/components\/ManualTraderWorkspace['"]/m.test(appTsx);
assert(
  !hasStaticImport,
  'Public app must not statically import ManualTraderWorkspace. Use dynamic import() instead.'
);

assert(
  !dashboardTsx.includes('manualClose('),
  'Public Dashboard must not invoke manualClose API.'
);

assert(
  !dashboardTsx.includes('매도하기'),
  'Public Dashboard must not render manual sell CTA.'
);

process.stdout.write('Public surface guard passed.\n');
