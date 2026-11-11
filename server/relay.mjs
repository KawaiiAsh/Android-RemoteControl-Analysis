import http from 'node:http';
import httpProxy from 'http-proxy';
import { WebSocketServer, WebSocket } from 'ws';

const HOST = '127.0.0.1';
const PORT = 5173;
const VITE_TARGET = 'http://127.0.0.1:5175';
const ADMIN_PASSWORD = process.env.YK_ADMIN_PASSWORD || '123';
const DEVICE_KEY = process.env.YK_DEVICE_KEY || 'yk-device-mumu-v1';
const admins = new Set();
const failedLogins = new Map();
const proxy = httpProxy.createProxyServer({ target: VITE_TARGET, ws: true });
const wss = new WebSocketServer({ noServer: true, maxPayload: 4 * 1024 * 1024 });
let device = null;
let deviceName = '';
let screenSharing = false;
let lastScreenText = null;
let framesInWindow = 0;
let currentFps = 0;

const httpServer = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ ok: true, deviceConnected: Boolean(device), dashboard: 'http://localhost:5173' }));
    return;
  }
  if (req.url === '/shutdown' && req.method === 'POST' && isLoopback(req.socket.remoteAddress)) {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{"ok":true}');
    setTimeout(() => {
      for (const admin of admins) admin.close(1001, 'Server shutting down');
      if (device) device.close(1001, 'Server shutting down');
      proxy.close();
      httpServer.close();
      process.exit(0);
    }, 100);
    return;
  }
  proxy.web(req, res);
});

proxy.on('error', (error, req, res) => {
  if (res && typeof res.writeHead === 'function' && !res.headersSent) {
    res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('Vite dev server is not ready. Start with npm run dev.');
  } else if (req?.socket) {
    req.socket.destroy();
  }
  if (error.code !== 'ECONNRESET') console.error('Vite proxy error:', error.message);
});

httpServer.on('upgrade', (request, socket, head) => {
  const url = new URL(request.url || '/', `http://${HOST}:${PORT}`);
  if (url.pathname === '/admin') {
    wss.handleUpgrade(request, socket, head, (ws) => wss.emit('connection', ws, request, 'admin'));
    return;
  }
  if (url.pathname === '/device' && url.searchParams.get('key') === DEVICE_KEY) {
    wss.handleUpgrade(request, socket, head, (ws) => wss.emit('connection', ws, request, 'device'));
    return;
  }
  if (url.pathname === '/device') {
    socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }
  proxy.ws(request, socket, head);
});

wss.on('connection', (ws, request, role) => {
  if (role === 'device') {
    if (device && device.readyState === WebSocket.OPEN) device.close(4000, 'Replaced by a newer device session');
    device = ws;
    const url = new URL(request.url || '/', `http://${HOST}:${PORT}`);
    deviceName = url.searchParams.get('name') || 'MuMu Android';
    screenSharing = false;
    lastScreenText = null;
    framesInWindow = 0;
    broadcastDeviceState();
    ws.on('message', (data, isBinary) => {
      if (isBinary) {
        framesInWindow++;
        for (const admin of admins) if (admin.readyState === WebSocket.OPEN) admin.send(data, { binary: true });
        return;
      }
      try {
        const message = JSON.parse(data.toString());
        if (message.type === 'screen_text' && Array.isArray(message.items)) {
          lastScreenText = message;
          for (const admin of admins) sendJson(admin, message);
          return;
        }
        if (message.type === 'device_info' && message.name) {
          deviceName = String(message.name).slice(0, 80);
          screenSharing = Boolean(message.screenSharing);
          broadcastDeviceState();
        }
      } catch { /* Ignore malformed device status messages. */ }
    });
    ws.on('close', () => {
      if (device === ws) {
        device = null;
        deviceName = '';
        screenSharing = false;
        lastScreenText = null;
        currentFps = 0;
        broadcastDeviceState();
      }
    });
    ws.on('error', () => {});
    return;
  }

  ws.on('message', (data, isBinary) => {
    if (isBinary) return;
    let message;
    try { message = JSON.parse(data.toString()); }
    catch { return sendJson(ws, { type: 'notice', message: '请求格式无效' }); }

    if (message.type === 'login') {
      const peer = request.socket.remoteAddress || 'local';
      const rate = failedLogins.get(peer);
      if (rate && Date.now() - rate.startedAt < 60_000 && rate.count >= 8) {
        sendJson(ws, { type: 'login_error', message: '尝试次数过多，请 60 秒后重试' });
        return;
      }
      if (message.password !== ADMIN_PASSWORD) {
        recordFailure(peer);
        sendJson(ws, { type: 'login_error', message: '密码不正确' });
        return;
      }
      failedLogins.delete(peer);
      ws.isAdmin = true;
      admins.add(ws);
      sendJson(ws, { type: 'login_ok' });
      sendJson(ws, { type: 'device_state', connected: Boolean(device), name: deviceName, screenSharing });
      if (lastScreenText) sendJson(ws, lastScreenText);
      return;
    }

    if (message.type === 'logout') {
      admins.delete(ws);
      ws.isAdmin = false;
      return;
    }

    if (message.type === 'control' && ws.isAdmin) {
      if (!device || device.readyState !== WebSocket.OPEN) {
        sendJson(ws, { type: 'notice', message: 'MuMu 设备未连接' });
        return;
      }
      device.send(JSON.stringify({ type: 'control', ...message }));
    }
  });

  ws.on('close', () => admins.delete(ws));
  ws.on('error', () => {});
});

setInterval(() => {
  currentFps = framesInWindow;
  framesInWindow = 0;
  broadcastDeviceState();
}, 1000).unref();

httpServer.listen(PORT, HOST, () => {
  console.log(`yk dashboard, relay, and APK endpoint ready at http://${HOST}:${PORT}`);
});

function recordFailure(peer) {
  const now = Date.now();
  const current = failedLogins.get(peer);
  if (!current || now - current.startedAt > 60_000) failedLogins.set(peer, { startedAt: now, count: 1 });
  else current.count++;
}

function broadcastDeviceState() {
  const state = { type: 'device_state', connected: Boolean(device && device.readyState === WebSocket.OPEN), name: deviceName, screenSharing, fps: currentFps };
  for (const admin of admins) sendJson(admin, state);
}

function sendJson(ws, value) {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(value));
}

function isLoopback(address = '') {
  return address === '127.0.0.1' || address === '::1' || address === '::ffff:127.0.0.1';
}
