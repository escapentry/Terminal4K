import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState } from 'react';
import { FitAddon } from '@xterm/addon-fit';
import { WebLinksAddon } from '@xterm/addon-web-links';
import { Terminal } from '@xterm/xterm';
import type { ComputedFontInfo, SessionSnapshot, TerminalSettings, TerminalTheme } from '../types/terminal';
import { computeAutoFontSize, getMonoFontStack } from '../utils/terminalFont';

export interface TerminalViewportHandle {
  sendRawInput: (data: string) => void;
  clearTerminal: () => void;
  copySelection: () => Promise<boolean>;
  focus: () => void;
}

interface TerminalViewportProps {
  settings: TerminalSettings;
  theme: TerminalTheme;
  session: SessionSnapshot | null;
  ctrlArmed: boolean;
  altArmed: boolean;
  onConsumeModifiers: () => void;
  onSessionChange: (session: SessionSnapshot) => void;
  onFontComputed: (info: ComputedFontInfo) => void;
  onSelectionChange: (hasSelection: boolean) => void;
  onRestartRequested: () => void;
  onZoomStep: (delta: number) => void;
}

function base64ToUint8Array(base64: string): Uint8Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function utf8ToBase64(text: string): string {
  const bytes = new TextEncoder().encode(text);
  let binary = '';
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    const chunk = bytes.subarray(i, i + chunkSize);
    binary += String.fromCharCode(...chunk);
  }
  return btoa(binary);
}

function playBellTone() {
  try {
    const AudioCtx = window.AudioContext || (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
    if (!AudioCtx) return;
    const ctx = new AudioCtx();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(760, ctx.currentTime);
    gain.gain.setValueAtTime(0.06, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.09);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.1);
    setTimeout(() => {
      ctx.close().catch(() => {});
    }, 180);
  } catch {
    // Audio playback blocked or unavailable
  }
}

function applyCtrlModifier(input: string): string {
  if (input.length !== 1) return input;
  const code = input.charCodeAt(0);
  if (code >= 97 && code <= 122) {
    return String.fromCharCode(code - 96);
  }
  if (code >= 64 && code <= 95) {
    return String.fromCharCode(code - 64);
  }
  if (input === ' ') return '\x00';
  return input;
}

export const TerminalViewport = forwardRef<TerminalViewportHandle, TerminalViewportProps>(
  function TerminalViewport(
    {
      settings,
      theme,
      session,
      ctrlArmed,
      altArmed,
      onConsumeModifiers,
      onSessionChange,
      onFontComputed,
      onSelectionChange,
      onRestartRequested,
      onZoomStep,
    },
    ref,
  ) {
    const containerRef = useRef<HTMLDivElement | null>(null);
    const termRef = useRef<Terminal | null>(null);
    const fitAddonRef = useRef<FitAddon | null>(null);
    const sendQueueRef = useRef<Promise<void>>(Promise.resolve());
    const lastSentSizeRef = useRef<{ cols: number; rows: number }>({ cols: 0, rows: 0 });
    const [bellFlash, setBellFlash] = useState(false);

    const settingsRef = useRef(settings);
    const sessionRef = useRef(session);
    const ctrlArmedRef = useRef(ctrlArmed);
    const altArmedRef = useRef(altArmed);
    const onConsumeModifiersRef = useRef(onConsumeModifiers);
    const onSessionChangeRef = useRef(onSessionChange);
    const onFontComputedRef = useRef(onFontComputed);
    const onSelectionChangeRef = useRef(onSelectionChange);
    const onRestartRequestedRef = useRef(onRestartRequested);
    const onZoomStepRef = useRef(onZoomStep);

    settingsRef.current = settings;
    sessionRef.current = session;
    ctrlArmedRef.current = ctrlArmed;
    altArmedRef.current = altArmed;
    onConsumeModifiersRef.current = onConsumeModifiers;
    onSessionChangeRef.current = onSessionChange;
    onFontComputedRef.current = onFontComputed;
    onSelectionChangeRef.current = onSelectionChange;
    onRestartRequestedRef.current = onRestartRequested;
    onZoomStepRef.current = onZoomStep;

    const enqueueInput = useCallback((rawText: string) => {
      if (!rawText) return;
      const encoded = utf8ToBase64(rawText);
      sendQueueRef.current = sendQueueRef.current
        .then(async () => {
          await fetch('/api/terminal/input', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ data: encoded, encoding: 'base64' }),
          });
        })
        .catch(() => {
          // Network hiccup ignored; SSE reconnect handles state recovery
        });
    }, []);

    const syncPtySize = useCallback((cols: number, rows: number) => {
      if (cols < 10 || rows < 2) return;
      if (lastSentSizeRef.current.cols === cols && lastSentSizeRef.current.rows === rows) return;
      lastSentSizeRef.current = { cols, rows };
      fetch('/api/terminal/resize', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ cols, rows }),
      }).catch(() => {});
    }, []);

    const updateTypographyAndFit = useCallback(() => {
      const term = termRef.current;
      const fitAddon = fitAddonRef.current;
      const container = containerRef.current;
      if (!term || !fitAddon || !container) return;

      const currentSettings = settingsRef.current;
      const rect = container.getBoundingClientRect();
      const isCoarsePointer = window.matchMedia('(pointer: coarse)').matches;

      let targetFontSize = currentSettings.manualFontSize;
      let deviceProfile = 'Manual';
      let targetColsHint = 0;

      if (currentSettings.autoFontSize) {
        const computed = computeAutoFontSize({
          containerWidth: rect.width,
          viewportWidth: window.innerWidth,
          viewportHeight: window.innerHeight,
          devicePixelRatio: window.devicePixelRatio || 1,
          isCoarsePointer,
          fontOffset: currentSettings.autoFontOffset,
        });
        targetFontSize = computed.fontSize;
        deviceProfile = computed.deviceProfile;
        targetColsHint = computed.targetColsHint;
      }

      const fontStack = getMonoFontStack(currentSettings.fontFamily);
      let changed = false;
      if (term.options.fontSize !== targetFontSize) {
        term.options.fontSize = targetFontSize;
        changed = true;
      }
      if (term.options.fontFamily !== fontStack) {
        term.options.fontFamily = fontStack;
        changed = true;
      }
      if (term.options.lineHeight !== currentSettings.lineHeight) {
        term.options.lineHeight = currentSettings.lineHeight;
        changed = true;
      }

      onFontComputedRef.current({
        fontSize: targetFontSize,
        deviceProfile,
        targetColsHint,
      });

      if (rect.width > 0 && rect.height > 0) {
        try {
          fitAddon.fit();
          syncPtySize(term.cols, term.rows);
          if (changed) {
            term.refresh(0, Math.max(0, term.rows - 1));
          }
        } catch {
          // Ignore transient fit error while unmounting
        }
      }
    }, [syncPtySize]);

    useImperativeHandle(
      ref,
      () => ({
        sendRawInput: (data: string) => {
          if (sessionRef.current?.status === 'exited') {
            if (data === '\r' || data === '\n') {
              onRestartRequestedRef.current();
            }
            return;
          }
          enqueueInput(data);
          termRef.current?.focus();
        },
        clearTerminal: () => {
          termRef.current?.clear();
          fetch('/api/terminal/clear', { method: 'POST' }).catch(() => {});
          termRef.current?.focus();
        },
        copySelection: async () => {
          const selection = termRef.current?.getSelection();
          if (!selection) return false;
          try {
            await navigator.clipboard.writeText(selection);
            return true;
          } catch {
            return false;
          }
        },
        focus: () => {
          termRef.current?.focus();
        },
      }),
      [enqueueInput],
    );

    // Initialize xterm.js and EventSource stream once
    useEffect(() => {
      const container = containerRef.current;
      if (!container) return;

      const term = new Terminal({
        allowProposedApi: true,
        fontFamily: getMonoFontStack(settingsRef.current.fontFamily),
        fontSize: settingsRef.current.manualFontSize,
        lineHeight: settingsRef.current.lineHeight,
        drawBoldTextInBrightColors: settingsRef.current.boldBright,
        cursorStyle: settingsRef.current.cursorStyle,
        cursorBlink: settingsRef.current.cursorBlink,
        scrollback: settingsRef.current.scrollback,
        theme: theme.xterm,
        convertEol: false,
        macOptionIsMeta: true,
        rightClickSelectsWord: true,
      });

      const fitAddon = new FitAddon();
      const webLinksAddon = new WebLinksAddon();
      term.loadAddon(fitAddon);
      term.loadAddon(webLinksAddon);
      term.open(container);

      termRef.current = term;
      fitAddonRef.current = fitAddon;

      term.attachCustomKeyEventHandler((event) => {
        if (event.type !== 'keydown') return true;

        if (sessionRef.current?.status === 'exited' && event.key === 'Enter') {
          event.preventDefault();
          onRestartRequestedRef.current();
          return false;
        }

        const isModifier = event.ctrlKey || event.metaKey;
        if (isModifier && event.key.toLowerCase() === 'c' && term.hasSelection()) {
          const text = term.getSelection();
          if (text) {
            navigator.clipboard.writeText(text).catch(() => {});
            term.clearSelection();
            onSelectionChangeRef.current(false);
          }
          return false;
        }

        if (event.ctrlKey && !event.altKey && (event.key === '=' || event.key === '+')) {
          event.preventDefault();
          onZoomStepRef.current(0.5);
          return false;
        }
        if (event.ctrlKey && !event.altKey && event.key === '-') {
          event.preventDefault();
          onZoomStepRef.current(-0.5);
          return false;
        }
        return true;
      });

      const dataDisposable = term.onData((raw) => {
        if (sessionRef.current?.status === 'exited') {
          if (raw === '\r') {
            onRestartRequestedRef.current();
          }
          return;
        }

        let out = raw;
        if (settingsRef.current.backspaceMode === 'ctrl-h' && out === '\x7f') {
          out = '\x08';
        }

        const hasCtrl = ctrlArmedRef.current;
        const hasAlt = altArmedRef.current;
        if (hasCtrl || hasAlt) {
          if (hasCtrl) {
            out = applyCtrlModifier(out);
          }
          if (hasAlt) {
            out = `\x1b${out}`;
          }
          onConsumeModifiersRef.current();
        }

        enqueueInput(out);
      });

      const selectionDisposable = term.onSelectionChange(() => {
        const hasSel = term.hasSelection();
        onSelectionChangeRef.current(hasSel);
        if (hasSel && settingsRef.current.copyOnSelect) {
          const selected = term.getSelection();
          if (selected) {
            navigator.clipboard.writeText(selected).catch(() => {});
          }
        }
      });

      const bellDisposable = term.onBell(() => {
        const mode = settingsRef.current.bellMode;
        if (mode === 'visual') {
          setBellFlash(true);
          setTimeout(() => setBellFlash(false), 140);
        } else if (mode === 'audio') {
          playBellTone();
        }
      });

      updateTypographyAndFit();
      document.fonts?.ready.then(() => {
        updateTypographyAndFit();
      });

      const resizeObserver = new ResizeObserver(() => {
        requestAnimationFrame(() => updateTypographyAndFit());
      });
      resizeObserver.observe(container);

      const onWindowResize = () => updateTypographyAndFit();
      window.addEventListener('resize', onWindowResize);

      // Connect SSE stream to persistent backend terminal service
      const eventSource = new EventSource('/api/terminal/stream');

      eventSource.addEventListener('init', (ev) => {
        try {
          const payload = JSON.parse((ev as MessageEvent).data) as {
            session: SessionSnapshot;
            replay: string;
          };
          term.reset();
          if (payload.replay) {
            term.write(base64ToUint8Array(payload.replay));
          }
          onSessionChangeRef.current(payload.session);
          requestAnimationFrame(() => {
            updateTypographyAndFit();
            term.focus();
          });
        } catch {
          // Ignore parse error
        }
      });

      eventSource.addEventListener('output', (ev) => {
        try {
          const payload = JSON.parse((ev as MessageEvent).data) as { data: string };
          if (payload.data) {
            term.write(base64ToUint8Array(payload.data));
          }
        } catch {
          // Ignore parse error
        }
      });

      eventSource.addEventListener('session', (ev) => {
        try {
          const nextSession = JSON.parse((ev as MessageEvent).data) as SessionSnapshot;
          onSessionChangeRef.current(nextSession);
        } catch {
          // Ignore parse error
        }
      });

      eventSource.addEventListener('exit', (ev) => {
        try {
          const payload = JSON.parse((ev as MessageEvent).data) as {
            code: number;
            session: SessionSnapshot;
          };
          onSessionChangeRef.current(payload.session);
          term.writeln('');
          term.writeln(`\x1b[2m[Process completed (code ${payload.code}) — press Enter to start new session]\x1b[0m`);
        } catch {
          // Ignore parse error
        }
      });

      return () => {
        eventSource.close();
        window.removeEventListener('resize', onWindowResize);
        resizeObserver.disconnect();
        dataDisposable.dispose();
        selectionDisposable.dispose();
        bellDisposable.dispose();
        term.dispose();
        termRef.current = null;
        fitAddonRef.current = null;
      };
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [enqueueInput, updateTypographyAndFit]);

    // Sync dynamic settings & theme changes without tearing down persistent session
    useEffect(() => {
      const term = termRef.current;
      if (!term) return;

      term.options.theme = theme.xterm;
      term.options.cursorStyle = settings.cursorStyle;
      term.options.cursorBlink = settings.cursorBlink;
      term.options.scrollback = settings.scrollback;
      term.options.drawBoldTextInBrightColors = settings.boldBright;

      updateTypographyAndFit();
      document.fonts?.ready.then(() => {
        updateTypographyAndFit();
      });
    }, [settings, theme, updateTypographyAndFit]);

    return (
      <div
        className="relative flex-1 min-h-0 w-full overflow-hidden px-2.5 py-2 sm:px-4 sm:py-3"
        style={{ backgroundColor: theme.xterm.background }}
        onClick={() => termRef.current?.focus()}
      >
        <div ref={containerRef} className="h-full w-full" />
        {bellFlash && (
          <div
            aria-hidden="true"
            className="pointer-events-none absolute inset-0 transition-opacity duration-100"
            style={{
              backgroundColor: theme.isLight ? 'rgba(0, 0, 0, 0.08)' : 'rgba(255, 255, 255, 0.08)',
            }}
          />
        )}
      </div>
    );
  },
);
