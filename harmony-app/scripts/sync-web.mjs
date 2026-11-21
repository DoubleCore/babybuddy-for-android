import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const harmonyRoot = path.resolve(__dirname, '..');
const repoRoot = path.resolve(harmonyRoot, '..');

const webSourceDir = path.join(repoRoot, 'web');
const rawfileWebDir = path.join(harmonyRoot, 'entry', 'src', 'main', 'resources', 'rawfile', 'web');
const appScopeMediaDir = path.join(harmonyRoot, 'AppScope', 'resources', 'base', 'media');

const webFiles = [
  'index.html',
  'app.js',
  'styles.css',
  'who-data.js',
  'pose-watch-lab.js',
  'logo.svg',
];

fs.mkdirSync(rawfileWebDir, { recursive: true });
for (const file of webFiles) {
  const src = path.join(webSourceDir, file);
  const dst = path.join(rawfileWebDir, file);
  if (!fs.existsSync(src)) {
    throw new Error(`Missing source web asset: ${src}`);
  }
  fs.copyFileSync(src, dst);
}

const iconTargets = [
  path.join(harmonyRoot, 'entry', 'src', 'main', 'resources', 'base', 'media'),
  path.join(harmonyRoot, 'wearable', 'src', 'main', 'resources', 'base', 'media'),
];

for (const targetDir of iconTargets) {
  fs.mkdirSync(targetDir, { recursive: true });
  fs.copyFileSync(path.join(appScopeMediaDir, 'background.svg'), path.join(targetDir, 'background.svg'));
  fs.copyFileSync(path.join(appScopeMediaDir, 'foreground.svg'), path.join(targetDir, 'foreground.svg'));
  fs.copyFileSync(path.join(webSourceDir, 'logo.svg'), path.join(targetDir, 'startIcon.svg'));
}

console.log(`Synced ${webFiles.length} web files into ${path.relative(repoRoot, rawfileWebDir)} and refreshed HarmonyOS media icons.`);
