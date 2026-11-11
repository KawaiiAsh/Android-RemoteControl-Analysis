import { spawn, execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

const vite = spawn(process.execPath, [
  'node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '5175', '--strictPort', '--clearScreen', 'false',
], { stdio: 'inherit', windowsHide: true });
let relay = null;
let stopping = false;

async function stop() {
  if (stopping) return;
  stopping = true;
  if (relay && relay.exitCode === null) relay.kill();
  if (vite.exitCode === null) vite.kill();
  await delay(200);
  process.exit(0);
}

vite.on('exit', stop);
process.on('SIGINT', stop);
process.on('SIGTERM', stop);

try {
  await waitForVite();
  relay = spawn(process.execPath, ['server/relay.mjs'], { stdio: 'inherit', windowsHide: true });
  relay.on('exit', stop);
  relay.on('error', (error) => {
    console.error('Could not start the 5173 local relay:', error.message);
    stop();
  });
  await waitForRelay();
  console.log('Open the dashboard at http://localhost:5173');
  configureAdbReverse();
} catch (error) {
  console.error(error.message);
  await stop();
}

async function waitForVite() {
  for (let attempt = 0; attempt < 80; attempt++) {
    if (vite.exitCode !== null) throw new Error('Vite could not start on internal port 5175.');
    try {
      const response = await fetch('http://127.0.0.1:5175');
      if (response.ok) return;
    } catch { /* Wait for the development server to bind. */ }
    await delay(250);
  }
  throw new Error('Vite did not become ready on internal port 5175.');
}

async function waitForRelay() {
  for (let attempt = 0; attempt < 40; attempt++) {
    if (relay?.exitCode !== null && relay?.exitCode !== undefined) throw new Error('The local relay could not start on port 5173.');
    try {
      const response = await fetch('http://127.0.0.1:5173/health');
      if (response.ok) return;
    } catch { /* Wait for the relay to bind. */ }
    await delay(150);
  }
  throw new Error('The local relay did not become ready on port 5173.');
}

function configureAdbReverse() {
  const candidates = [
    process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk', 'platform-tools', 'adb.exe'),
    'C:\\Program Files\\Netease\\MuMu\\nx_device\\15.0\\shell\\adb.exe',
    'C:\\Program Files\\Netease\\MuMu\\nx_main\\adb.exe',
  ].filter(Boolean);
  const adb = candidates.find((candidate) => existsSync(candidate));
  if (!adb) {
    console.log('ADB was not found; start MuMu and configure adb reverse tcp:5173 tcp:5173 if needed.');
    return;
  }
  try {
    const listOnlineSerials = () => execFileSync(adb, ['devices'], { encoding: 'utf8', windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] })
      .split(/\r?\n/)
      .filter((line) => /^\S+\s+device(?:\s|$)/.test(line))
      .map((line) => line.trim().split(/\s+/)[0]);
    let serials = listOnlineSerials();
    if (serials.length === 0) {
      try { execFileSync(adb, ['connect', '127.0.0.1:7555'], { windowsHide: true, stdio: 'ignore' }); } catch {}
      serials = listOnlineSerials();
    }
    let ready = 0;
    for (const serial of serials) {
      try {
        execFileSync(adb, ['-s', serial, 'reverse', 'tcp:5173', 'tcp:5173'], { windowsHide: true, stdio: 'ignore' });
        ready++;
      } catch { /* Continue if another listed ADB target is unavailable. */ }
    }
    console.log(ready ? `ADB reverse is active for ${ready} device(s) on port 5173.` : 'No online ADB device found; start MuMu and rerun npm run dev.');
  } catch {
    console.log('Could not configure ADB reverse; run adb reverse tcp:5173 tcp:5173 manually if needed.');
  }
}
