/**
 * main.js — Electron Main Process for PromptCam Desktop
 *
 * Responsibilities:
 *   • Create the BrowserWindow and load the renderer UI
 *   • Wire IPC handlers between the renderer and backend modules
 *   • mDNS discovery of "promptcam._tcp" services (the phone app)
 *   • Manage StreamClient, TelepromptServer, VirtualCamera, VoiceSync
 *   • Application menu and lifecycle / cleanup
 */

const { app, BrowserWindow, ipcMain, Menu } = require('electron');
const path = require('path');
const Bonjour = require('bonjour-service').Bonjour;

// ── Internal modules ────────────────────────────────────────────────────
const StreamClient = require('./lib/stream-client');
const TelepromptServer = require('./lib/teleprompt-server');
const VirtualCamera = require('./lib/virtual-camera');
const VoiceSync = require('./lib/voice-sync');

// ── Globals ─────────────────────────────────────────────────────────────
let mainWindow = null;
let streamClient = null;
let telepromptServer = null;
let virtualCamera = null;
let voiceSync = null;
let bonjour = null;
let discoveredDevices = []; // [{ name, host, port }]
let currentConnection = null; // { host, port }

// ── Window creation ─────────────────────────────────────────────────────

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 860,
    minWidth: 960,
    minHeight: 600,
    title: 'PromptCam Desktop',
    backgroundColor: '#1a1a2e',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false, // preload needs require() for IPC
    },
  });

  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));

  // Open DevTools in development
  if (process.env.PROMPTCAM_DEV === '1') {
    mainWindow.webContents.openDevTools({ mode: 'detach' });
  }

  mainWindow.on('closed', () => {
    mainWindow = null;
  });
}

// ── Application menu ────────────────────────────────────────────────────

function buildMenu() {
  const template = [
    {
      label: 'File',
      submenu: [
        {
          label: 'Disconnect',
          accelerator: 'CmdOrCtrl+D',
          click: () => handleDisconnect(),
        },
        { type: 'separator' },
        { role: 'quit' },
      ],
    },
    {
      label: 'View',
      submenu: [
        { role: 'reload' },
        { role: 'forceReload' },
        { type: 'separator' },
        { role: 'toggleDevTools' },
        { type: 'separator' },
        { role: 'resetZoom' },
        { role: 'zoomIn' },
        { role: 'zoomOut' },
        { type: 'separator' },
        { role: 'togglefullscreen' },
      ],
    },
    {
      label: 'Help',
      submenu: [
        {
          label: 'About PromptCam',
          click: () => {
            if (mainWindow) {
              mainWindow.webContents.send('about');
            }
          },
        },
      ],
    },
  ];

  // macOS requires the first menu item to be the app name
  if (process.platform === 'darwin') {
    template.unshift({
      label: app.name,
      submenu: [
        { role: 'about' },
        { type: 'separator' },
        { role: 'services' },
        { type: 'separator' },
        { role: 'hide' },
        { role: 'hideOthers' },
        { role: 'unhide' },
        { type: 'separator' },
        { role: 'quit' },
      ],
    });
  }

  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

// ── mDNS discovery ──────────────────────────────────────────────────────

function startDiscovery() {
  if (bonjour) bonjour.destroy();
  bonjour = new Bonjour();

  // Look for promptcam._tcp services advertised by the phone app
  const browser = bonjour.find({ type: 'promptcam', protocol: 'tcp' });

  browser.on('up', (service) => {
    const host = service.host || (service.addresses && service.addresses[0]);
    const port = service.port || 8554;
    const name = service.name || 'PromptCam Device';

    // Avoid duplicates
    if (discoveredDevices.some((d) => d.host === host)) return;

    const device = { name, host, port };
    discoveredDevices.push(device);
    console.log(`[mDNS] Discovered: ${name} at ${host}:${port}`);

    if (mainWindow) {
      mainWindow.webContents.send('discovered-devices', discoveredDevices);
    }
  });

  browser.on('down', (service) => {
    const host = service.host || (service.addresses && service.addresses[0]);
    discoveredDevices = discoveredDevices.filter((d) => d.host !== host);
    if (mainWindow) {
      mainWindow.webContents.send('discovered-devices', discoveredDevices);
    }
  });

  // Also listen for teleprompter service (port 8555)
  const tpBrowser = bonjour.find({ type: 'promptcam-tp', protocol: 'tcp' });
  tpBrowser.on('up', (service) => {
    const host = service.host || (service.addresses && service.addresses[0]);
    const port = service.port || 8555;
    // Attach the teleprompter port info to the matching device
    const device = discoveredDevices.find((d) => d.host === host);
    if (device && !device.tpPort) {
      device.tpPort = port;
    }
  });
}

function stopDiscovery() {
  if (bonjour) {
    bonjour.destroy();
    bonjour = null;
  }
}

// ── IPC: Connection management ──────────────────────────────────────────

/**
 * Connect to the phone: starts StreamClient (video+audio) and TelepromptServer.
 * Expected payload: { host: string, port?: number, tpPort?: number }
 */
async function handleConnect(_event, payload) {
  const host = payload.host;
  const videoPort = payload.port || 8554;
  const tpPort = payload.tpPort || 8555;

  if (!host) {
    return { success: false, error: 'No host specified' };
  }

  try {
    // Tear down existing connections
    if (streamClient) streamClient.destroy();
    if (telepromptServer) telepromptServer.destroy();

    // Stream client — receives video + audio from the phone
    streamClient = new StreamClient(host, videoPort);
    streamClient.on('connected', () => {
      sendStatus('connected', `Connected to ${host}:${videoPort}`);
    });
    streamClient.on('disconnected', () => {
      sendStatus('disconnected', 'Stream disconnected');
    });
    streamClient.on('error', (err) => {
      sendStatus('error', `Stream error: ${err.message}`);
    });
    streamClient.on('videoFrame', (frameData) => {
      // Forward raw H.264 NAL units to the renderer for WebCodecs decoding
      if (mainWindow) {
        mainWindow.webContents.send('stream-video-frame', frameData);
      }
      // Feed to virtual camera if active
      if (virtualCamera && virtualCamera.isActive) {
        virtualCamera.feedFrame(frameData);
      }
    });
    streamClient.on('audioFrame', (audioData) => {
      // Forward AAC frames to the renderer for decoding / playback
      if (mainWindow) {
        mainWindow.webContents.send('stream-audio-frame', audioData);
      }
      // Feed to voice sync for recognition
      if (voiceSync && voiceSync.isRunning) {
        voiceSync.feedAudio(audioData);
      }
    });

    // Teleprompt server — sends script text + scroll to phone
    telepromptServer = new TelepromptServer(host, tpPort);
    telepromptServer.on('connected', () => {
      console.log(`[Teleprompt] Connected to ${host}:${tpPort}`);
    });
    telepromptServer.on('disconnected', () => {
      console.log('[Teleprompt] Disconnected');
    });
    telepromptServer.on('error', (err) => {
      console.error('[Teleprompt] Error:', err.message);
    });

    // Start connecting
    streamClient.connect();
    telepromptServer.connect();

    currentConnection = { host, port: videoPort, tpPort };
    sendStatus('connecting', `Connecting to ${host}…`);

    return { success: true };
  } catch (err) {
    return { success: false, error: err.message };
  }
}

function handleDisconnect() {
  if (streamClient) {
    streamClient.destroy();
    streamClient = null;
  }
  if (telepromptServer) {
    telepromptServer.destroy();
    telepromptServer = null;
  }
  if (voiceSync) {
    voiceSync.stop();
    voiceSync = null;
  }
  if (virtualCamera) {
    virtualCamera.stop();
    virtualCamera = null;
  }
  currentConnection = null;
  sendStatus('disconnected', 'Disconnected');
  if (mainWindow) {
    mainWindow.webContents.send('connection-status', {
      status: 'disconnected',
      message: 'Disconnected',
    });
  }
}

// ── IPC: Script & scroll ────────────────────────────────────────────────

function handleSendScript(_event, { text, fontSize, speed }) {
  if (!telepromptServer) {
    return { success: false, error: 'Not connected to phone' };
  }
  return telepromptServer.sendScript(text, fontSize || 24, speed || 1.0);
}

function handleSendScrollPosition(_event, percent) {
  if (!telepromptServer) {
    return { success: false, error: 'Not connected to phone' };
  }
  return telepromptServer.sendScrollPosition(percent);
}

function handleSendCommand(_event, command) {
  if (!telepromptServer) {
    return { success: false, error: 'Not connected to phone' };
  }
  return telepromptServer.sendCommand(command);
}

// ── IPC: Voice sync ─────────────────────────────────────────────────────

function handleStartVoiceSync(_event, scriptText) {
  if (!streamClient) {
    return { success: false, error: 'Not connected to phone stream' };
  }

  if (voiceSync) {
    voiceSync.stop();
  }

  voiceSync = new VoiceSync(scriptText);

  voiceSync.on('partialResult', (text) => {
    if (mainWindow) mainWindow.webContents.send('voice-partial', text);
  });
  voiceSync.on('scrollPosition', (percent) => {
    if (mainWindow) mainWindow.webContents.send('voice-scroll', percent);
    // Also send to phone via teleprompt server
    if (telepromptServer) telepromptServer.sendScrollPosition(percent);
  });
  voiceSync.on('matchConfidence', (confidence) => {
    if (mainWindow) mainWindow.webContents.send('voice-confidence', confidence);
  });

  voiceSync.start();
  return { success: true };
}

function handleStopVoiceSync() {
  if (voiceSync) {
    voiceSync.stop();
    voiceSync = null;
    return { success: true };
  }
  return { success: false, error: 'Voice sync not running' };
}

// ── IPC: Virtual camera ─────────────────────────────────────────────────

function handleStartVirtualCamera(_event, { width, height }) {
  if (virtualCamera) {
    virtualCamera.stop();
  }
  virtualCamera = new VirtualCamera({ width: width || 1280, height: height || 720 });
  const result = virtualCamera.start();
  return result;
}

function handleStopVirtualCamera() {
  if (virtualCamera) {
    virtualCamera.stop();
    virtualCamera = null;
    return { success: true };
  }
  return { success: false, error: 'Virtual camera not running' };
}

// ── Utility: send status to renderer ────────────────────────────────────

function sendStatus(status, message) {
  if (mainWindow) {
    mainWindow.webContents.send('connection-status', { status, message });
  }
}

// ── IPC registration ────────────────────────────────────────────────────

function registerIpcHandlers() {
  ipcMain.handle('connect', handleConnect);
  ipcMain.handle('disconnect', () => {
    handleDisconnect();
    return { success: true };
  });
  ipcMain.handle('send-script', handleSendScript);
  ipcMain.handle('send-scroll', handleSendScrollPosition);
  ipcMain.handle('send-command', handleSendCommand);
  ipcMain.handle('start-voice-sync', handleStartVoiceSync);
  ipcMain.handle('stop-voice-sync', handleStopVoiceSync);
  ipcMain.handle('start-virtual-camera', handleStartVirtualCamera);
  ipcMain.handle('stop-virtual-camera', handleStopVirtualCamera);
  ipcMain.handle('get-discovered-devices', () => discoveredDevices);
}

// ── App lifecycle ───────────────────────────────────────────────────────

app.whenReady().then(() => {
  createWindow();
  buildMenu();
  registerIpcHandlers();
  startDiscovery();

  // Re-scan every 30 seconds
  setInterval(() => {
    if (!currentConnection) {
      startDiscovery();
    }
  }, 30000);
});

app.on('window-all-closed', () => {
  // macOS: apps stay active until explicitly quit
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('activate', () => {
  // macOS: re-create window when dock icon is clicked
  if (BrowserWindow.getAllWindows().length === 0) {
    createWindow();
  }
});

app.on('before-quit', () => {
  // Clean up all resources
  stopDiscovery();
  handleDisconnect();
});
