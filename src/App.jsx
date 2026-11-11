import React, { useCallback, useEffect, useRef, useState } from 'react';

const socketUrl = `ws://${window.location.hostname || 'localhost'}:${window.location.port || '5173'}/admin`;

function Icon({ name }) {
  const paths = {
    back: <><path d="M19 12H5"/><path d="m12 19-7-7 7-7"/></>,
    home: <><path d="m3 10 9-7 9 7"/><path d="M5 9v11h14V9"/><path d="M9 20v-6h6v6"/></>,
    apps: <><rect x="4" y="4" width="6" height="6" rx="1"/><rect x="14" y="4" width="6" height="6" rx="1"/><rect x="4" y="14" width="6" height="6" rx="1"/><rect x="14" y="14" width="6" height="6" rx="1"/></>,
    bell: <><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9"/><path d="M10 21h4"/></>,
    send: <><path d="m22 2-7 20-4-9-9-4Z"/><path d="M22 2 11 13"/></>,
    wifi: <><path d="M5 12.55a11 11 0 0 1 14.08 0"/><path d="M1.42 9a16 16 0 0 1 21.16 0"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><path d="M12 20h.01"/></>,
    lock: <><rect x="4" y="10" width="16" height="11" rx="2"/><path d="M8 10V7a4 4 0 1 1 8 0v3"/></>,
  };
  return <svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>;
}

export default function App() {
  const [password, setPassword] = useState('');
  const [unlocked, setUnlocked] = useState(false);
  const [socketState, setSocketState] = useState('connecting');
  const [deviceOnline, setDeviceOnline] = useState(false);
  const [screenSharing, setScreenSharing] = useState(false);
  const [deviceName, setDeviceName] = useState('等待 MuMu 模拟器');
  const [notice, setNotice] = useState('');
  const [text, setText] = useState('');
  const [screenText, setScreenText] = useState({ app: '', items: [] });
  const [frame, setFrame] = useState('');
  const [fps, setFps] = useState(0);
  const socketRef = useRef(null);
  const unlockedRef = useRef(false);
  const screenSharingRef = useRef(false);
  const imageRef = useRef(null);
  const pointerRef = useRef(null);
  const frameTimesRef = useRef([]);
  const frameUrlRef = useRef('');
  const frameTimeoutRef = useRef(0);
  unlockedRef.current = unlocked;
  screenSharingRef.current = screenSharing;

  useEffect(() => {
    let alive = true;
    let retryTimer;
    let currentSocket;
    const connect = () => {
      if (!alive) return;
      currentSocket = new WebSocket(socketUrl);
      currentSocket.binaryType = 'blob';
      socketRef.current = currentSocket;
      currentSocket.onopen = () => alive && setSocketState('connected');
      currentSocket.onclose = () => {
        if (!alive) return;
        setSocketState('connecting');
        setDeviceOnline(false);
        setScreenText({ app: '', items: [] });
        screenSharingRef.current = false;
        setScreenSharing(false);
        setFps(0);
        window.clearTimeout(frameTimeoutRef.current);
        if (frameUrlRef.current) URL.revokeObjectURL(frameUrlRef.current);
        frameUrlRef.current = '';
        setFrame('');
        if (unlockedRef.current) {
          unlockedRef.current = false;
          setUnlocked(false);
          setNotice('本地控制服务暂时断开，重新连接后请再输入密码');
        }
        retryTimer = window.setTimeout(connect, 1200);
      };
      currentSocket.onerror = () => currentSocket.close();
      currentSocket.onmessage = (event) => {
        if (event.data instanceof Blob) {
          screenSharingRef.current = true;
          setScreenSharing(true);
          window.clearTimeout(frameTimeoutRef.current);
          frameTimeoutRef.current = window.setTimeout(() => {
            screenSharingRef.current = false;
            setScreenSharing(false);
            setFps(0);
            if (frameUrlRef.current) URL.revokeObjectURL(frameUrlRef.current);
            frameUrlRef.current = '';
            setFrame('');
          }, 1800);
          const nextUrl = URL.createObjectURL(event.data);
          if (frameUrlRef.current) URL.revokeObjectURL(frameUrlRef.current);
          frameUrlRef.current = nextUrl;
          setFrame(nextUrl);
          const now = performance.now();
          const times = frameTimesRef.current.filter((time) => now - time < 1000);
          times.push(now);
          frameTimesRef.current = times;
          setFps(times.length);
          return;
        }
        try {
          const message = JSON.parse(event.data);
          if (message.type === 'login_ok') {
            setUnlocked(true);
            setNotice('');
            setPassword('');
          } else if (message.type === 'login_error') {
            setNotice(message.message || '密码错误');
          } else if (message.type === 'device_state') {
            const connected = Boolean(message.connected);
            const hasSharingState = typeof message.screenSharing === 'boolean';
            const sharing = hasSharingState ? message.screenSharing : (connected && screenSharingRef.current);
            setDeviceOnline(connected);
            screenSharingRef.current = sharing;
            setScreenSharing(sharing);
            setDeviceName(message.name || (message.connected ? 'Android 设备' : '等待 MuMu 模拟器'));
            if (!connected || (hasSharingState && !sharing)) {
              window.clearTimeout(frameTimeoutRef.current);
              setFps(0);
              if (frameUrlRef.current) URL.revokeObjectURL(frameUrlRef.current);
              frameUrlRef.current = '';
              setFrame('');
            }
            if (!connected) setScreenText({ app: '', items: [] });
            setNotice(connected ? '' : '请先在 MuMu 中打开 yk Remote APK');
          } else if (message.type === 'screen_text') {
            setScreenText({
              app: typeof message.app === 'string' ? message.app : '',
              items: Array.isArray(message.items) ? message.items.filter((item) => typeof item?.text === 'string' && item.text.trim()) : [],
            });
          } else if (message.type === 'notice') {
            setNotice(message.message || '');
          }
        } catch {
          setNotice('收到无法识别的服务响应');
        }
      };
    };
    connect();
    return () => {
      alive = false;
      window.clearTimeout(retryTimer);
      window.clearTimeout(frameTimeoutRef.current);
      currentSocket?.close();
      if (frameUrlRef.current) URL.revokeObjectURL(frameUrlRef.current);
    };
  }, []);

  const send = useCallback((payload) => {
    const socket = socketRef.current;
    if (!socket || socket.readyState !== WebSocket.OPEN || !unlocked) {
      setNotice('先输入 123 解锁控制台');
      return;
    }
    if (!deviceOnline) {
      setNotice('模拟器尚未连接。请确认 yk Remote 已在 MuMu 中运行');
      return;
    }
    socket.send(JSON.stringify({ type: 'control', ...payload }));
  }, [unlocked, deviceOnline]);

  const login = (event) => {
    event.preventDefault();
    const socket = socketRef.current;
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      setNotice('正在连接本地服务，请稍后再试');
      return;
    }
    socket.send(JSON.stringify({ type: 'login', password }));
  };

  const screenPoint = (event) => {
    const image = imageRef.current;
    if (image?.naturalWidth) {
      const rect = image.getBoundingClientRect();
      const scale = Math.min(rect.width / image.naturalWidth, rect.height / image.naturalHeight);
      const shownWidth = image.naturalWidth * scale;
      const shownHeight = image.naturalHeight * scale;
      const left = rect.left + (rect.width - shownWidth) / 2;
      const top = rect.top + (rect.height - shownHeight) / 2;
      const px = Math.max(0, Math.min(image.naturalWidth, (event.clientX - left) / scale));
      const py = Math.max(0, Math.min(image.naturalHeight, (event.clientY - top) / scale));
      return { x: px / image.naturalWidth, y: py / image.naturalHeight, px, py };
    }
    return null;
  };

  const pointerDown = (event) => {
    const point = screenPoint(event);
    if (!point) return;
    pointerRef.current = { ...point, time: performance.now() };
    event.currentTarget.setPointerCapture(event.pointerId);
  };

  const pointerUp = (event) => {
    const start = pointerRef.current;
    const end = screenPoint(event);
    pointerRef.current = null;
    if (!start || !end) return;
    const distance = Math.hypot(end.px - start.px, end.py - start.py);
    if (distance < 10) send({ action: 'tap', x: end.x, y: end.y, normalized: true });
    else send({ action: 'swipe', x1: start.x, y1: start.y, x2: end.x, y2: end.y, normalized: true, duration: Math.max(100, Math.min(1200, performance.now() - start.time)) });
  };

  const sendText = () => {
    if (!text) return;
    send({ action: 'text', text });
    setText('');
  };

  const orderedScreenText = [...screenText.items].sort((a, b) => (a.top ?? 0) - (b.top ?? 0) || (a.left ?? 0) - (b.left ?? 0));

  useEffect(() => {
    const onKey = (event) => {
      if (!unlocked || ['INPUT', 'TEXTAREA'].includes(document.activeElement?.tagName)) return;
      if (event.key === 'Escape') { event.preventDefault(); send({ action: 'back' }); }
      if (event.key === 'Home') { event.preventDefault(); send({ action: 'home' }); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [send, unlocked]);

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand"><div className="brand-mark">yk</div><div><strong>yk Control</strong><span>本地手机控制台</span></div></div>
        <div className="top-status"><span className={`dot ${socketState === 'connected' ? 'online' : ''}`} />{socketState === 'connected' ? '本地服务已连接' : '正在连接 localhost:5173'}</div>
      </header>

      <main className="layout">
        <section className="control-column">
          <div className="intro"><div className="eyebrow">ANDROID · MUMU</div><h1>远程控制</h1><p>电脑网页连接本地服务，直接操作 MuMu 中的 Android 屏幕。</p></div>
          <div className="device-card">
            <div className="device-icon">M</div><div className="device-meta"><strong>{deviceName}</strong><span>{deviceOnline ? (screenSharing ? '控制与画面已连接' : '控制已连接 · 画面未开启') : '等待设备连接'}</span></div><div className={`device-pill ${deviceOnline ? 'is-online' : ''}`}><span className="dot"/>{deviceOnline ? '在线' : '离线'}</div>
          </div>

          <div className="panel nav-panel">
            <div className="panel-heading"><div><h2>系统导航</h2><span>使用电脑快捷操作</span></div></div>
            <div className="nav-grid">
              <button onClick={() => send({ action: 'back' })}><Icon name="back"/><span>返回</span><kbd>Esc</kbd></button>
              <button onClick={() => send({ action: 'home' })}><Icon name="home"/><span>主页</span><kbd>Home</kbd></button>
              <button onClick={() => send({ action: 'recents' })}><Icon name="apps"/><span>最近任务</span></button>
              <button onClick={() => send({ action: 'notifications' })}><Icon name="bell"/><span>通知栏</span></button>
            </div>
          </div>

          <div className="panel text-panel">
            <div className="panel-heading"><div><h2>发送文字</h2><span>发送到手机当前聚焦的非密码输入框</span></div></div>
            <div className="text-entry"><input value={text} onChange={(e) => setText(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && sendText()} placeholder="输入要发送到手机的内容" maxLength={4000}/><button className="send-button" onClick={sendText} aria-label="发送文字"><Icon name="send"/></button></div>
            <div className="footnote">未开屏幕共享时也可发送文字，但需要先在手机上聚焦输入框。密码输入框会被拒绝写入。</div>
          </div>

          <div className="network-note"><Icon name="wifi"/><span>仅在本机运行。APK 默认通过 MuMu ADB 反向端口连接电脑。</span></div>
        </section>

        <section className="screen-column">
          <div className="screen-toolbar"><div><h2>{screenSharing && frame ? '设备屏幕' : '屏幕文字'}</h2><span>{screenSharing && frame ? `${fps} FPS · 本地实时流` : deviceOnline ? '无障碍文字读取 · 不需要屏幕共享' : '打开 MuMu 中的 yk Remote APK 以连接'}</span></div><div className={`connection-tag ${deviceOnline ? 'connected' : ''}`}><span className="dot"/>{deviceOnline ? '控制已连接' : '未连接'}</div></div>
          <div className="screen-frame" onContextMenu={(e) => e.preventDefault()} onWheel={(event) => {
            if (!deviceOnline || !unlocked) return;
            event.preventDefault();
            const down = event.deltaY > 0;
            send({ action: 'swipe', x1: 0.5, y1: down ? 0.68 : 0.32, x2: 0.5, y2: down ? 0.32 : 0.68, normalized: true, duration: 260 });
          }}>
            {frame ? <img ref={imageRef} src={frame} alt="Android 屏幕" draggable="false" onPointerDown={pointerDown} onPointerUp={pointerUp}/> : deviceOnline && !screenSharing ? <div className="screen-text-view"><div className="screen-text-header"><strong>{screenText.app || '无障碍可见文字'}</strong><span>{orderedScreenText.length} 项</span></div>{orderedScreenText.length ? <div className="screen-text-list">{orderedScreenText.map((item, index) => <button className="screen-text-row" key={`${item.text}-${item.left}-${item.top}-${index}`} onClick={() => send({ action: 'tap', x: item.x ?? ((item.left + item.right) / 2), y: item.y ?? ((item.top + item.bottom) / 2), normalized: true })}><span>{item.text}</span><small>点按 · {Math.round((item.top ?? 0) * 100)}%</small></button>)}</div> : <div className="screen-text-empty"><strong>正在读取屏幕文字…</strong><span>文字来自无障碍界面信息，不会采集屏幕图像。</span></div>}</div> : <div className="empty-screen"><div className="phone-outline"><div className="phone-camera"/><div className="phone-lines"><i/><i/><i/></div><div className="phone-button"/></div><strong>{deviceOnline ? '等待屏幕画面' : '等待 MuMu 设备'}</strong><span>{deviceOnline ? '请确认 APK 中的屏幕共享正在运行' : '在模拟器里打开 yk Remote APK 并连接控制通道'}</span></div>}
          </div>
          <div className="screen-hint"><span><b>点击</b> 点按</span><span><b>拖动</b> 滑动</span><span><b>滚轮</b> 滚动</span></div>
          {notice && <div className="notice"><span className="notice-dot"/>{notice}</div>}
        </section>
      </main>

      {!unlocked && <div className="modal-backdrop"><form className="login-card" onSubmit={login}><div className="login-icon"><Icon name="lock"/></div><div className="eyebrow">LOCAL ADMIN</div><h2>解锁控制台</h2><p>输入管理密码以连接你的 MuMu 模拟器。</p><label htmlFor="admin-password">管理密码</label><input id="admin-password" type="password" autoComplete="current-password" inputMode="numeric" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="输入密码" autoFocus/><button className="unlock-button" type="submit">解锁并连接</button><div className="login-foot"><span className={`dot ${socketState === 'connected' ? 'online' : ''}`}/>{socketState === 'connected' ? '仅本机可访问' : '等待本地服务'}</div>{notice && <div className="login-error">{notice}</div>}</form></div>}
    </div>
  );
}
