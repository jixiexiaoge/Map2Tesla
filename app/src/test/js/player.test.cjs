const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { test } = require('node:test');
const vm = require('node:vm');

const html = readFileSync(resolve(__dirname, '../../main/assets/amap2tesla_player.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

function player() {
  const nodes = new Map();
  function element(id) {
    if (!nodes.has(id)) nodes.set(id, {
      style: {}, dataset: {}, classList: { toggle() {}, add() {}, remove() {} },
      handlers: {}, addEventListener(type, handler) { this.handlers[type] = handler; }, textContent: '',
    });
    return nodes.get(id);
  }
  const requests = [];
  let health = {
    running: true, width: 1280, height: 720, frameSequence: 10,
    frameAgeMs: 50, fps: 20, displayId: 1, interactive: true,
    playerRevision: '20261025-1',
  };
  class Socket {
    static OPEN = 1;
    static CONNECTING = 0;
    static CLOSED = 3;
    constructor() { this.readyState = 0; this.messages = []; }
    addEventListener() {}
    send(message) { this.messages.push(JSON.parse(message)); }
  }
  const context = vm.createContext({
    document: {
      getElementById: element, body: element('body'),
      documentElement: { clientWidth: 1280, clientHeight: 720 },
      addEventListener() {},
    },
    window: { addEventListener() {}, visualViewport: { width: 1280, height: 720, addEventListener() {} } },
    location: { protocol: 'http:', host: 'localhost', href: 'http://localhost/', replace() {} },
    WebSocket: Socket, console, URL, AbortController, performance: { now: () => 5000 },
    setTimeout() {}, clearTimeout() {}, setInterval() {}, clearInterval() {},
    fetch: async (url, options) => {
      requests.push({ url, options });
      return { ok: true, json: async () => health };
    },
  });
  vm.runInContext(script, context);
  return { context, nodes, requests, setHealth: value => { health = value; } };
}

test('player bootstrap reaches health polling without missing function references', async () => {
  const app = player();
  await vm.runInContext('updateStats()', app.context);
  assert.ok(app.requests.some(request => request.url.startsWith('/health')));
  assert.equal(app.nodes.get('status').style.display, 'none');
});

test('collapsed dock is bottom-left and expands without forwarding a screen tap', () => {
  const app = player();
  const rule = html.match(/#dockExpandBtn\s*\{([^}]+)\}/)[1];
  assert.match(rule, /bottom:\s*max\(12px,\s*env\(safe-area-inset-bottom\)\)/);
  assert.match(rule, /left:\s*max\(12px,\s*env\(safe-area-inset-left\)\)/);
  assert.doesNotMatch(rule, /(?:^|;)\s*top:/);
  let stopped = 0;
  const event = { preventDefault() {}, stopPropagation() { stopped++; } };
  app.nodes.get('dockToggleBtn').handlers.click(event);
  assert.equal(vm.runInContext('dockCollapsed', app.context), true);
  app.nodes.get('dockExpandBtn').handlers.click(event);
  assert.equal(vm.runInContext('dockCollapsed', app.context), false);
  assert.equal(stopped, 2);
});

test('player advertises permanently free access without a trial or membership offer', async () => {
  const app = player();
  await vm.runInContext('updateStats()', app.context);
  assert.equal(app.nodes.get('promoBanner').style.display, 'flex');
  assert.match(html, /本软件永久免费，所有功能不限时/);
  assert.doesNotMatch(html, /每天可试用|会员不限时/);
});

test('fresh video hides overlay even before control opens; stopped service shows it', async () => {
  const app = player();
  await vm.runInContext('updateStats()', app.context);
  assert.equal(app.nodes.get('status').style.display, 'none');
  app.setHealth({ running: false, frameSequence: 0, frameAgeMs: 10000 });
  await vm.runInContext('updateStats()', app.context);
  assert.equal(app.nodes.get('status').style.display, 'block');
});

test('viewport fallback sends actual visible dimensions using POST', async () => {
  const app = player();
  for (const [width, height] of [[1280, 720], [390, 844], [1920, 480]]) {
    app.context.window.visualViewport.width = width;
    app.context.window.visualViewport.height = height;
    await vm.runInContext('reportViewport()', app.context);
    const request = app.requests.filter(item => item.url.startsWith('/viewport')).at(-1);
    assert.equal(request.options.method, 'POST');
    assert.match(request.url, new RegExp(`width=${width}&height=${height}`));
  }
});

test('native CarPlay activity selects the CarPlay browser presentation', async () => {
  const app = player();
  await vm.runInContext(`updateModePresentation({
    running: true, currentActivity: CARPLAY_HOST_ACTIVITY, width: 1280, height: 720, fps: 20
  })`, app.context);
  assert.equal(app.nodes.get('statusTitle').textContent, 'CarPlay 正在准备');
  assert.match(app.nodes.get('statusSubtitle').textContent, /CarPlay/);
});

test('projection modes do not render the top-right debug badge', () => {
  const app = player();
  for (const health of [
    { running: true, width: 1280, height: 720, fps: 20 },
    { running: true, currentActivity: 'com.shilapi.xcertplay.CarPlayHostActivity', fps: 20 },
    { running: true, interactive: false, fps: 20 },
  ]) {
    app.context.testHealth = health;
    vm.runInContext('updateModePresentation(testHealth)', app.context);
    assert.equal(app.nodes.has('sessionBadge'), false);
  }
  assert.doesNotMatch(html, /sessionBadge/);
});

test('a click with no pointer events forwards a tap without duplicating normal pointer taps', () => {
  const app = player();
  const screen = app.nodes.get('screen');
  const socket = new app.context.WebSocket();
  socket.readyState = app.context.WebSocket.OPEN;
  vm.runInContext('interactive = true', app.context);
  app.context.testSocket = socket;
  vm.runInContext('control = testSocket', app.context);
  const event = {
    clientX: 640, clientY: 360, pointerId: 9, preventDefault() {},
  };
  screen.getBoundingClientRect = () => ({ left: 0, top: 0, width: 1280, height: 720 });
  screen.handlers.click(event);
  assert.deepEqual(socket.messages.map(message => message.action), [0, 1]);
  socket.messages.length = 0;
  screen.handlers.pointerdown(event);
  screen.handlers.pointerup(event);
  screen.handlers.click(event);
  assert.deepEqual(socket.messages.map(message => message.action), [0, 1]);
});

test('pointer capture failure does not prevent forwarding touch', () => {
  const app = player();
  const screen = app.nodes.get('screen');
  const socket = new app.context.WebSocket();
  socket.readyState = app.context.WebSocket.OPEN;
  vm.runInContext('interactive = true', app.context);
  app.context.testSocket = socket;
  vm.runInContext('control = testSocket', app.context);
  screen.getBoundingClientRect = () => ({ left: 0, top: 0, width: 1280, height: 720 });
  screen.setPointerCapture = () => { throw new Error('unsupported'); };
  screen.handlers.pointerdown({ clientX: 640, clientY: 360, pointerId: 9, preventDefault() {} });
  assert.equal(socket.messages.length, 1);
  assert.equal(socket.messages[0].action, 0);
});

test('AirPlay browser is ready before a sender connects, and automatically shows new frames', async () => {
  const app = player();
  const health = {
    running: true, interactive: false, waitingForSender: true,
    receiverName: 'Test AirPlay', frameSequence: 0, frameAgeMs: -1,
  };
  app.setHealth(health);
  await vm.runInContext('updateStats()', app.context);
  assert.match(app.nodes.get('statusReason').textContent, /等待 AirPlay 设备连接/);
  assert.match(app.nodes.get('statusSubtitle').textContent, /Test AirPlay/);
  assert.equal(app.nodes.get('screen').style.visibility, 'hidden');
  assert.equal(app.nodes.get('dockBar').style.display, 'none');
  assert.equal(app.nodes.get('featureGrid').style.display, 'none');
  assert.equal(vm.runInContext('retryReloadStream', app.context), false);

  app.setHealth({ ...health, waitingForSender: false, frameSequence: 5, frameAgeMs: 20 });
  await vm.runInContext('updateStats()', app.context);
  assert.equal(app.nodes.get('status').style.display, 'none');
  assert.equal(app.nodes.get('screen').style.visibility, 'visible');

  app.setHealth({ ...health, frameSequence: 5 });
  await vm.runInContext('updateStats()', app.context);
  assert.equal(app.nodes.get('status').style.display, 'block');
  assert.equal(app.nodes.get('screen').style.visibility, 'hidden');
  assert.equal(vm.runInContext('streamHealthy', app.context), false);

  app.setHealth({ ...health, waitingForSender: false, frameSequence: 6, frameAgeMs: 10 });
  await vm.runInContext('updateStats()', app.context);
  assert.equal(app.nodes.get('status').style.display, 'none');
});

test('cleared or stale AirPlay frames cannot hide the waiting overlay', async () => {
  const app = player();
  for (const frameAgeMs of [-1, 10001]) {
    app.setHealth({
      running: true, interactive: false, waitingForSender: false,
      frameSequence: 50, frameAgeMs,
    });
    await vm.runInContext('updateStats()', app.context);
    assert.equal(app.nodes.get('status').style.display, 'block');
    assert.equal(app.nodes.get('screen').style.visibility, 'hidden');
  }
});

test('late audio response cannot attach after playback was stopped', async () => {
  const app = player();
  await vm.runInContext('updateStats()', app.context);
  let finishFetch;
  let cancelled = false;
  let requestedReader = false;
  app.context.fetch = () => new Promise(resolve => { finishFetch = resolve; });
  const pending = vm.runInContext('receivePcmAudio(2, audioGeneration)', app.context);
  vm.runInContext('stopBrowserAudio()', app.context);
  finishFetch({
    ok: true,
    body: {
      cancel: async () => { cancelled = true; },
      getReader: () => { requestedReader = true; },
    },
  });
  await pending;
  assert.equal(cancelled, true);
  assert.equal(requestedReader, false);
  assert.equal(vm.runInContext('audioReader', app.context), null);
});

test('stopping audio ignores an old read completing during a new session', async () => {
  const app = player();
  await vm.runInContext('updateStats()', app.context);
  let completeRead;
  let markReadStarted;
  const readStarted = new Promise(resolve => { markReadStarted = resolve; });
  app.context.fetch = async () => ({
    ok: true,
    body: { getReader: () => ({
      read: () => new Promise(resolve => {
        completeRead = resolve;
        markReadStarted();
      }),
      cancel: async () => {},
    }) },
  });
  vm.runInContext('audioContext = { close: async () => {} }', app.context);
  const pending = vm.runInContext('receivePcmAudio(2, audioGeneration)', app.context);
  await readStarted;
  vm.runInContext('stopBrowserAudio(); sound.textContent = "new session"', app.context);
  completeRead({ done: false, value: Uint8Array.from([1, 2, 3, 4]) });
  await pending;
  assert.equal(vm.runInContext('audioChunks.length', app.context), 0);
  assert.equal(app.nodes.get('sound').textContent, 'new session');
});
