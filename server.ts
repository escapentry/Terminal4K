import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import express, { type Request, type Response } from 'express';

const PORT = Number(process.env.PORT) || 3000;
const MAX_SCROLLBACK_BYTES = 512 * 1024;
const RC_FILE_PATH = path.join(os.tmpdir(), 'com_thallyace_terminal_rc');

function ensureRcFile() {
  const rcContent = [
    'if [ -f /etc/bash.bashrc ]; then . /etc/bash.bashrc; fi',
    'if [ -f ~/.bashrc ]; then . ~/.bashrc; fi',
    'shopt -s checkwinsize 2>/dev/null || true',
    'export TERM=xterm-256color',
    'export COLORTERM=truecolor',
    'export TERMINAL_PACKAGE=com.thallyace.terminal',
    'alias ls="ls --color=auto"',
    'alias ll="ls -la --color=auto"',
    'alias la="ls -A --color=auto"',
    'alias cls="clear"',
    'PS1="\\[\\e[1;36m\\]com.thallyace.terminal\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\$ "',
    '',
  ].join('\n');
  fs.writeFileSync(RC_FILE_PATH, rcContent, { mode: 0o600 });
}

interface SessionSnapshot {
  sessionId: string;
  pid: number | null;
  status: 'running' | 'exited';
  exitCode: number | null;
  startedAt: number;
  endedAt: number | null;
  cols: number;
  rows: number;
  shell: string;
  packageName: string;
}

class PersistentTerminalService {
  private proc: ChildProcessWithoutNullStreams | null = null;
  private clients = new Set<Response>();
  private scrollbackChunks: Buffer[] = [];
  private scrollbackBytes = 0;
  private stdoutBuffer = '';

  private sessionId = '';
  private pid: number | null = null;
  private status: 'running' | 'exited' = 'exited';
  private exitCode: number | null = null;
  private startedAt = 0;
  private endedAt: number | null = null;
  private cols = 80;
  private rows = 24;
  private readonly shell = fs.existsSync('/bin/bash') ? '/bin/bash' : '/bin/sh';

  constructor() {
    ensureRcFile();
    this.startNewSession(this.cols, this.rows);
  }

  public getSnapshot(): SessionSnapshot {
    return {
      sessionId: this.sessionId,
      pid: this.pid,
      status: this.status,
      exitCode: this.exitCode,
      startedAt: this.startedAt,
      endedAt: this.endedAt,
      cols: this.cols,
      rows: this.rows,
      shell: this.shell,
      packageName: 'com.thallyace.terminal',
    };
  }

  public startNewSession(cols?: number, rows?: number): SessionSnapshot {
    if (this.proc && this.status === 'running') {
      try {
        this.proc.kill('SIGTERM');
      } catch {
        // Ignore cleanup errors
      }
      this.proc = null;
    }

    if (typeof cols === 'number' && cols >= 10) this.cols = Math.min(500, Math.round(cols));
    if (typeof rows === 'number' && rows >= 2) this.rows = Math.min(500, Math.round(rows));

    ensureRcFile();
    this.scrollbackChunks = [];
    this.scrollbackBytes = 0;
    this.stdoutBuffer = '';
    this.sessionId = crypto.randomUUID();
    this.pid = null;
    this.status = 'running';
    this.exitCode = null;
    this.startedAt = Date.now();
    this.endedAt = null;

    const bridgePath = path.resolve(process.cwd(), 'pty_bridge.py');
    const child = spawn('python3', [bridgePath, String(this.cols), String(this.rows), process.cwd(), RC_FILE_PATH], {
      stdio: ['pipe', 'pipe', 'pipe'],
      env: process.env,
    });

    this.proc = child;

    child.stdout.on('data', (chunk: Buffer) => {
      if (this.proc !== child) return;
      this.stdoutBuffer += chunk.toString('utf8');
      let newlineIdx = this.stdoutBuffer.indexOf('\n');
      while (newlineIdx !== -1) {
        const line = this.stdoutBuffer.slice(0, newlineIdx).trim();
        this.stdoutBuffer = this.stdoutBuffer.slice(newlineIdx + 1);
        if (line) {
          this.handleBridgeMessage(line);
        }
        newlineIdx = this.stdoutBuffer.indexOf('\n');
      }
    });

    child.stderr.on('data', (chunk: Buffer) => {
      if (this.proc !== child) return;
      const text = chunk.toString('utf8');
      if (text.trim()) {
        this.appendOutput(Buffer.from(text, 'utf8'));
      }
    });

    child.on('close', (code) => {
      if (this.proc !== child) return;
      if (this.status === 'running') {
        this.markExited(typeof code === 'number' ? code : 0);
      }
      this.proc = null;
    });

    this.broadcast('session', this.getSnapshot());
    return this.getSnapshot();
  }

  private handleBridgeMessage(line: string) {
    try {
      const msg = JSON.parse(line) as { type: string; data?: string; code?: number; pid?: number };
      if (msg.type === 'ready' && typeof msg.pid === 'number') {
        this.pid = msg.pid;
        this.broadcast('session', this.getSnapshot());
      } else if (msg.type === 'output' && typeof msg.data === 'string') {
        const buf = Buffer.from(msg.data, 'base64');
        this.appendOutput(buf);
      } else if (msg.type === 'exit') {
        this.markExited(typeof msg.code === 'number' ? msg.code : 0);
      }
    } catch {
      // Ignore malformed lines
    }
  }

  private appendOutput(buf: Buffer) {
    if (buf.length === 0) return;
    this.scrollbackChunks.push(buf);
    this.scrollbackBytes += buf.length;
    while (this.scrollbackBytes > MAX_SCROLLBACK_BYTES && this.scrollbackChunks.length > 1) {
      const removed = this.scrollbackChunks.shift();
      if (removed) {
        this.scrollbackBytes -= removed.length;
      }
    }
    this.broadcast('output', { data: buf.toString('base64') });
  }

  private markExited(code: number) {
    if (this.status === 'exited') return;
    this.status = 'exited';
    this.exitCode = code;
    this.endedAt = Date.now();
    this.broadcast('exit', {
      code,
      session: this.getSnapshot(),
    });
  }

  public sendInput(dataBase64: string): boolean {
    if (!this.proc || this.status !== 'running') {
      return false;
    }
    try {
      const msg = JSON.stringify({ type: 'input', data: dataBase64 }) + '\n';
      this.proc.stdin.write(msg);
      return true;
    } catch {
      return false;
    }
  }

  public resize(cols: number, rows: number): void {
    const safeCols = Math.max(10, Math.min(500, Math.round(cols)));
    const safeRows = Math.max(2, Math.min(500, Math.round(rows)));
    if (safeCols === this.cols && safeRows === this.rows) return;
    this.cols = safeCols;
    this.rows = safeRows;
    if (this.proc && this.status === 'running') {
      try {
        const msg = JSON.stringify({ type: 'resize', cols: safeCols, rows: safeRows }) + '\n';
        this.proc.stdin.write(msg);
      } catch {
        // Ignore write error
      }
    }
    this.broadcast('session', this.getSnapshot());
  }

  public sendSignal(sig: number): boolean {
    if (!this.proc || this.status !== 'running') return false;
    try {
      const msg = JSON.stringify({ type: 'signal', sig }) + '\n';
      this.proc.stdin.write(msg);
      return true;
    } catch {
      return false;
    }
  }

  public clearBuffer(): void {
    this.scrollbackChunks = [];
    this.scrollbackBytes = 0;
  }

  public attachClient(req: Request, res: Response): void {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });
    res.flushHeaders?.();

    this.clients.add(res);

    const replayBuffer = Buffer.concat(this.scrollbackChunks).toString('base64');
    this.sendEvent(res, 'init', {
      session: this.getSnapshot(),
      replay: replayBuffer,
    });

    const keepAlive = setInterval(() => {
      try {
        res.write(': keepalive\n\n');
      } catch {
        clearInterval(keepAlive);
      }
    }, 20000);

    req.on('close', () => {
      clearInterval(keepAlive);
      this.clients.delete(res);
    });
  }

  private sendEvent(res: Response, event: string, data: unknown) {
    res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
  }

  private broadcast(event: string, data: unknown) {
    const payload = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
    for (const client of this.clients) {
      try {
        client.write(payload);
      } catch {
        this.clients.delete(client);
      }
    }
  }

  public shutdown() {
    if (this.proc) {
      try {
        this.proc.kill('SIGTERM');
      } catch {
        // Ignore
      }
      this.proc = null;
    }
  }
}

async function startServer() {
  const app = express();
  app.use(express.json({ limit: '1mb' }));

  const terminalService = new PersistentTerminalService();

  app.get('/api/terminal/status', (_req, res) => {
    res.json(terminalService.getSnapshot());
  });

  app.get('/api/terminal/stream', (req, res) => {
    terminalService.attachClient(req, res);
  });

  app.post('/api/terminal/input', (req, res) => {
    const { data, encoding } = req.body as { data?: string; encoding?: 'base64' | 'utf8' };
    if (typeof data !== 'string') {
      res.status(400).json({ error: 'Missing input data' });
      return;
    }
    const base64Data = encoding === 'base64' ? data : Buffer.from(data, 'utf8').toString('base64');
    const accepted = terminalService.sendInput(base64Data);
    res.json({ ok: accepted, status: terminalService.getSnapshot().status });
  });

  app.post('/api/terminal/resize', (req, res) => {
    const { cols, rows } = req.body as { cols?: number; rows?: number };
    if (typeof cols !== 'number' || typeof rows !== 'number') {
      res.status(400).json({ error: 'Invalid cols or rows' });
      return;
    }
    terminalService.resize(cols, rows);
    res.json({ ok: true, cols: terminalService.getSnapshot().cols, rows: terminalService.getSnapshot().rows });
  });

  app.post('/api/terminal/signal', (req, res) => {
    const { sig } = req.body as { sig?: number };
    const signalNum = typeof sig === 'number' ? sig : 2;
    const ok = terminalService.sendSignal(signalNum);
    res.json({ ok });
  });

  app.post('/api/terminal/restart', (req, res) => {
    const { cols, rows } = (req.body ?? {}) as { cols?: number; rows?: number };
    const session = terminalService.startNewSession(cols, rows);
    res.json({ ok: true, session });
  });

  app.post('/api/terminal/clear', (_req, res) => {
    terminalService.clearBuffer();
    res.json({ ok: true });
  });

  app.get('/api/android/workflow', (_req, res) => {
    const workflowPath = path.resolve(process.cwd(), '.github/workflows/build-android.yml');
    if (!fs.existsSync(workflowPath)) {
      res.status(404).send('Workflow file not found');
      return;
    }
    res.setHeader('Content-Type', 'text/yaml; charset=utf-8');
    res.sendFile(workflowPath);
  });

  app.get('/api/android/download-source', (_req, res) => {
    const rootDir = process.cwd();
    const pyScript = [
      'import io, os, sys, zipfile',
      'root = sys.argv[1]',
      'buf = io.BytesIO()',
      'exclude_dirs = {".gradle", "build", ".cxx", "node_modules", "dist"}',
      'with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:',
      '    wf = os.path.join(root, ".github", "workflows", "build-android.yml")',
      '    if os.path.isfile(wf):',
      '        zf.write(wf, ".github/workflows/build-android.yml")',
      '    readme = os.path.join(root, "README_ANDROID.md")',
      '    if os.path.isfile(readme):',
      '        zf.write(readme, "README_ANDROID.md")',
      '    android_dir = os.path.join(root, "android")',
      '    if os.path.isdir(android_dir):',
      '        for cur, dirs, files in os.walk(android_dir):',
      '            dirs[:] = [d for d in dirs if d not in exclude_dirs]',
      '            for fn in files:',
      '                if fn == "local.properties": continue',
      '                full = os.path.join(cur, fn)',
      '                rel = os.path.relpath(full, root)',
      '                zf.write(full, rel)',
      'sys.stdout.buffer.write(buf.getvalue())',
    ].join('\n');

    const child = spawn('python3', ['-c', pyScript, rootDir], {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const chunks: Buffer[] = [];
    let errText = '';
    child.stdout.on('data', (d: Buffer) => chunks.push(d));
    child.stderr.on('data', (d: Buffer) => {
      errText += d.toString('utf8');
    });
    child.on('close', (code) => {
      if (code !== 0) {
        res.status(500).json({ error: errText || 'Failed to package Android source archive' });
        return;
      }
      const zipBuffer = Buffer.concat(chunks);
      res.setHeader('Content-Type', 'application/zip');
      res.setHeader('Content-Disposition', 'attachment; filename="Terminal-com.thallyace.terminal-github.zip"');
      res.setHeader('Content-Length', String(zipBuffer.length));
      res.send(zipBuffer);
    });
  });

  const distDir = path.resolve(process.cwd(), 'dist');
  const isProd = process.env.NODE_ENV === 'production' || (process.env.NODE_ENV !== 'development' && fs.existsSync(path.join(distDir, 'index.html')));

  if (isProd) {
    app.use(express.static(distDir));
    app.get('/{*splat}', (_req, res) => {
      res.sendFile(path.join(distDir, 'index.html'));
    });
  } else {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  }

  const server = app.listen(PORT, '0.0.0.0', () => {
    console.log(`com.thallyace.terminal service listening on http://0.0.0.0:${PORT}`);
  });

  const cleanup = () => {
    terminalService.shutdown();
    server.close(() => process.exit(0));
  };

  process.on('SIGINT', cleanup);
  process.on('SIGTERM', cleanup);
}

startServer().catch((err) => {
  console.error('Failed to start server:', err);
  process.exit(1);
});
