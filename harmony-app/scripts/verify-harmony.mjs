import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const harmonyRoot = path.resolve(__dirname, '..');
const repoRoot = path.resolve(harmonyRoot, '..');

function stripJsonComments(text) {
  return text
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:\\])\/\/.*$/gm, '$1')
    .replace(/,\s*([}\]])/g, '$1');
}

function readJson5(filePath) {
  const raw = fs.readFileSync(filePath, 'utf8');
  return JSON.parse(stripJsonComments(raw));
}

function sha256(filePath) {
  return crypto.createHash('sha256').update(fs.readFileSync(filePath)).digest('hex');
}

const errors = [];
function assert(cond, message) {
  if (!cond) {
    errors.push(message);
  }
}

// 1. Validate project-level files & modelVersion consistency
const rootBuildProfile = readJson5(path.join(harmonyRoot, 'build-profile.json5'));
const rootOhPkg = readJson5(path.join(harmonyRoot, 'oh-package.json5'));
const hvigorCfg = readJson5(path.join(harmonyRoot, 'hvigor', 'hvigor-config.json5'));
const appJson = readJson5(path.join(harmonyRoot, 'AppScope', 'app.json5'));

assert(
  rootOhPkg.modelVersion === hvigorCfg.modelVersion,
  `modelVersion mismatch: oh-package.json5 (${rootOhPkg.modelVersion}) != hvigor-config.json5 (${hvigorCfg.modelVersion})`
);
assert(
  rootBuildProfile.app.products[0].compatibleSdkVersion === '5.0.0(12)',
  'Expected compatibleSdkVersion 5.0.0(12)'
);
assert(appJson.app.bundleName === 'eu.pkgsoftware.babybuddy.harmony', 'Unexpected bundleName');

// 2. Validate AppScope resources
const appBaseStrings = readJson5(path.join(harmonyRoot, 'AppScope', 'resources', 'base', 'element', 'string.json'));
const appStringNames = new Set(appBaseStrings.string.map((s) => s.name));
assert(appStringNames.has('app_name'), 'Missing AppScope string: app_name');
assert(fs.existsSync(path.join(harmonyRoot, 'AppScope', 'resources', 'base', 'media', 'layered_image.json')), 'Missing AppScope layered_image.json');

// 3. Validate each module (entry & wearable)
const entryDeviceTypes = new Set();
for (const modDef of rootBuildProfile.modules) {
  const modDir = path.join(harmonyRoot, modDef.srcPath);
  const modBuild = readJson5(path.join(modDir, 'build-profile.json5'));
  readJson5(path.join(modDir, 'oh-package.json5'));
  const modJson = readJson5(path.join(modDir, 'src', 'main', 'module.json5'));
  const mod = modJson.module;

  assert(modBuild.apiType === 'stageMode', `${modDef.name}: apiType must be stageMode`);
  assert(mod.name === modDef.name, `Module name mismatch in ${modDef.name}`);

  // Disjoint deviceTypes check for multiple entry modules
  if (mod.type === 'entry') {
    for (const dt of mod.deviceTypes) {
      assert(!entryDeviceTypes.has(dt), `Overlapping entry deviceType detected: ${dt}`);
      entryDeviceTypes.add(dt);
    }
  }

  // Verify strings, colors, profiles, pages, abilities, network_config
  const resBase = path.join(modDir, 'src', 'main', 'resources', 'base');
  const stringJson = readJson5(path.join(resBase, 'element', 'string.json'));
  const stringSet = new Set(stringJson.string.map((s) => s.name));
  const colorJson = readJson5(path.join(resBase, 'element', 'color.json'));
  const colorSet = new Set(colorJson.color.map((c) => c.name));
  const mainPages = readJson5(path.join(resBase, 'profile', 'main_pages.json'));
  const netCfg = readJson5(path.join(resBase, 'profile', 'network_config.json'));

  assert(netCfg['network-security-config']?.['base-config']?.cleartextTrafficPermitted === true, `${modDef.name}: cleartextTrafficPermitted should be true for LAN watch server`);
  assert(!('networkSecurityConfig' in mod), `${modDef.name}: module.json5 must not contain Android-only networkSecurityConfig key`);

  for (const page of mainPages.src) {
    const etsFile = path.join(modDir, 'src', 'main', 'ets', `${page}.ets`);
    assert(fs.existsSync(etsFile), `${modDef.name}: Missing page file ${etsFile}`);
  }

  for (const ab of mod.abilities) {
    const abFile = path.join(modDir, 'src', 'main', ab.srcEntry);
    assert(fs.existsSync(abFile), `${modDef.name}: Missing ability file ${abFile}`);
    const checkRef = (ref) => {
      if (ref.startsWith('$string:')) {
        assert(stringSet.has(ref.slice(8)), `${modDef.name}: Unresolved string ref ${ref}`);
      } else if (ref.startsWith('$color:')) {
        assert(colorSet.has(ref.slice(7)), `${modDef.name}: Unresolved color ref ${ref}`);
      } else if (ref.startsWith('$media:')) {
        const name = ref.slice(7);
        const mediaDir = path.join(resBase, 'media');
        const found = ['.json', '.svg', '.png', '.jpg'].some((ext) => fs.existsSync(path.join(mediaDir, name + ext)));
        assert(found, `${modDef.name}: Unresolved media ref ${ref}`);
      }
    };
    checkRef(ab.description);
    checkRef(ab.label);
    checkRef(ab.icon);
    checkRef(ab.startWindowIcon);
    checkRef(ab.startWindowBackground);
  }

  for (const perm of mod.requestPermissions || []) {
    if (perm.name === 'ohos.permission.CAMERA') {
      assert(Boolean(perm.reason) && Boolean(perm.usedScene), `${modDef.name}: user_grant permission CAMERA requires reason and usedScene`);
      if (perm.reason.startsWith('$string:')) {
        assert(stringSet.has(perm.reason.slice(8)), `${modDef.name}: Unresolved permission reason ${perm.reason}`);
      }
    }
  }
}

// 4. Verify web/ <-> entry rawfile/web/ sync + JS syntax check
const webFiles = ['index.html', 'app.js', 'styles.css', 'who-data.js', 'pose-watch-lab.js', 'logo.svg'];
for (const file of webFiles) {
  const src = path.join(repoRoot, 'web', file);
  const dst = path.join(harmonyRoot, 'entry', 'src', 'main', 'resources', 'rawfile', 'web', file);
  assert(fs.existsSync(dst), `Missing synced rawfile: ${dst}`);
  if (fs.existsSync(dst)) {
    assert(sha256(src) === sha256(dst), `Out-of-sync rawfile web asset: ${file}`);
  }
}

for (const jsFile of ['app.js', 'pose-watch-lab.js', 'who-data.js']) {
  execFileSync(process.execPath, ['--check', path.join(repoRoot, 'web', jsFile)], { stdio: 'pipe' });
}

if (errors.length > 0) {
  console.error('HarmonyOS verification FAILED:');
  for (const err of errors) {
    console.error(`  - ${err}`);
  }
  process.exit(1);
}

console.log('HarmonyOS project verification PASSED (JSON5 configs, resources, multi-device entry modules, rawfile sync, and JS syntax).');
