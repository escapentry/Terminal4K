import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ClipboardPaste, Copy, Download, Eraser, RotateCcw, Settings, TerminalSquare } from 'lucide-react';
import { ExtraKeysBar } from './components/ExtraKeysBar';
import { SettingsDrawer } from './components/SettingsDrawer';
import { TerminalViewport, type TerminalViewportHandle } from './components/TerminalViewport';
import {
  DEFAULT_TERMINAL_SETTINGS,
  type ComputedFontInfo,
  type SessionSnapshot,
  type TerminalSettings,
} from './types/terminal';
import { TERMINAL_THEMES } from './utils/terminalThemes';

const SETTINGS_STORAGE_KEY = 'com.android.terminal.settings.v1';

function loadSettings(): TerminalSettings {
  try {
    const raw = localStorage.getItem(SETTINGS_STORAGE_KEY);
    if (!raw) return DEFAULT_TERMINAL_SETTINGS;
    const parsed = JSON.parse(raw) as Partial<TerminalSettings>;
    return {
      ...DEFAULT_TERMINAL_SETTINGS,
      ...parsed,
    };
  } catch {
    return DEFAULT_TERMINAL_SETTINGS;
  }
}

export default function App() {
  const [settings, setSettings] = useState<TerminalSettings>(loadSettings);
  const [session, setSession] = useState<SessionSnapshot | null>(null);
  const [computedFont, setComputedFont] = useState<ComputedFontInfo>({
    fontSize: 14.5,
    deviceProfile: 'Desktop',
    targetColsHint: 100,
  });
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [ctrlArmed, setCtrlArmed] = useState(false);
  const [altArmed, setAltArmed] = useState(false);
  const [hasSelection, setHasSelection] = useState(false);
  const [copiedFeedback, setCopiedFeedback] = useState(false);
  const [isTouchDevice, setIsTouchDevice] = useState(() =>
    typeof window !== 'undefined' ? window.matchMedia('(pointer: coarse)').matches : false,
  );

  const terminalRef = useRef<TerminalViewportHandle | null>(null);
  const theme = useMemo(() => TERMINAL_THEMES[settings.themeId] ?? TERMINAL_THEMES['android-dark'], [settings.themeId]);

  useEffect(() => {
    try {
      localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(settings));
    } catch {
      // Ignore storage quota errors
    }
  }, [settings]);

  useEffect(() => {
    const media = window.matchMedia('(pointer: coarse)');
    const onChange = (e: MediaQueryListEvent) => setIsTouchDevice(e.matches);
    setIsTouchDevice(media.matches);
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, []);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === ',') {
        e.preventDefault();
        setSettingsOpen((prev) => !prev);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  const updateSetting = useCallback(<K extends keyof TerminalSettings>(key: K, value: TerminalSettings[K]) => {
    setSettings((prev) => ({ ...prev, [key]: value }));
  }, []);

  const resetSettings = useCallback(() => {
    setSettings(DEFAULT_TERMINAL_SETTINGS);
  }, []);

  const handleRestartService = useCallback(async () => {
    try {
      const res = await fetch('/api/terminal/restart', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          cols: session?.cols ?? 80,
          rows: session?.rows ?? 24,
        }),
      });
      if (res.ok) {
        const data = (await res.json()) as { session: SessionSnapshot };
        terminalRef.current?.clearTerminal();
        setSession(data.session);
        terminalRef.current?.focus();
      }
    } catch {
      // Ignore restart error
    }
  }, [session?.cols, session?.rows]);

  const handleSendSigint = useCallback(async () => {
    await fetch('/api/terminal/signal', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sig: 2 }),
    }).catch(() => {});
    terminalRef.current?.focus();
  }, []);

  const handleCopy = useCallback(async () => {
    const ok = await terminalRef.current?.copySelection();
    if (ok) {
      setCopiedFeedback(true);
      setTimeout(() => setCopiedFeedback(false), 1200);
    }
    terminalRef.current?.focus();
  }, []);

  const handlePaste = useCallback(async () => {
    try {
      const text = await navigator.clipboard.readText();
      if (text) {
        terminalRef.current?.sendRawInput(text);
      }
    } catch {
      // Clipboard read denied or unavailable
    }
    terminalRef.current?.focus();
  }, []);

  const handleZoomStep = useCallback((delta: number) => {
    setSettings((prev) => {
      if (prev.autoFontSize) {
        const nextOffset = Math.max(-3, Math.min(6, prev.autoFontOffset + delta));
        return { ...prev, autoFontOffset: nextOffset };
      }
      const nextManual = Math.max(10, Math.min(26, prev.manualFontSize + delta));
      return { ...prev, manualFontSize: nextManual };
    });
  }, []);

  const handleConsumeModifiers = useCallback(() => {
    setCtrlArmed(false);
    setAltArmed(false);
  }, []);

  const showExtraKeys =
    settings.extraKeysMode === 'always' || (settings.extraKeysMode === 'auto' && isTouchDevice);

  const shellLabel = session?.shell ? session.shell.split('/').pop() ?? 'bash' : 'bash';

  return (
    <div
      className="h-dvh w-screen flex flex-col overflow-hidden select-none"
      style={{
        backgroundColor: theme.chromeBg,
        color: theme.chromeText,
      }}
    >
      {/* Single-Row Top Bar */}
      <header
        className="shrink-0 h-12 px-3 sm:px-4 border-b flex items-center justify-between gap-4"
        style={{
          backgroundColor: theme.chromeSurface,
          borderColor: theme.chromeBorder,
        }}
      >
        <div className="flex items-center gap-2.5 min-w-0">
          <TerminalSquare className="w-4 h-4 shrink-0" style={{ color: theme.accent }} />
          <span className="font-mono text-sm font-semibold tracking-tight truncate whitespace-nowrap">
            com.android.terminal
          </span>
          <span
            className="hidden sm:inline-flex items-center gap-1.5 text-xs font-mono tabular-nums whitespace-nowrap shrink-0"
            style={{ color: theme.chromeMuted }}
          >
            <span aria-hidden="true">·</span>
            <span>{session?.status === 'exited' ? `exited (${session.exitCode ?? 0})` : shellLabel}</span>
            {session && (
              <>
                <span aria-hidden="true">·</span>
                <span>
                  {session.cols}×{session.rows}
                </span>
              </>
            )}
            <span aria-hidden="true">·</span>
            <span>{computedFont.fontSize.toFixed(1)}px</span>
          </span>
        </div>

        <div className="flex items-center gap-1 shrink-0">
          {hasSelection && (
            <button
              type="button"
              onClick={handleCopy}
              title="Copy selection"
              className="min-h-[38px] px-2.5 rounded-md text-xs font-medium whitespace-nowrap shrink-0 flex items-center gap-1.5 transition-colors cursor-pointer"
              style={{
                backgroundColor: theme.accent,
                color: theme.accentText,
              }}
            >
              <Copy className="w-3.5 h-3.5" />
              <span>{copiedFeedback ? 'Copied' : 'Copy'}</span>
            </button>
          )}

          <button
            type="button"
            onClick={handlePaste}
            title="Paste from clipboard"
            aria-label="Paste from clipboard"
            className="min-h-[38px] min-w-[38px] px-2.5 rounded-md text-xs font-medium whitespace-nowrap shrink-0 flex items-center gap-1.5 transition-colors cursor-pointer"
            style={{ color: theme.chromeMuted }}
          >
            <ClipboardPaste className="w-4 h-4" />
            <span className="hidden md:inline">Paste</span>
          </button>

          <button
            type="button"
            onClick={() => terminalRef.current?.clearTerminal()}
            title="Clear terminal scrollback"
            aria-label="Clear terminal scrollback"
            className="min-h-[38px] min-w-[38px] px-2.5 rounded-md text-xs font-medium whitespace-nowrap shrink-0 flex items-center gap-1.5 transition-colors cursor-pointer"
            style={{ color: theme.chromeMuted }}
          >
            <Eraser className="w-4 h-4" />
            <span className="hidden md:inline">Clear</span>
          </button>

          <a
            href="/api/android/download-source"
            download="Terminal-com.android.terminal-github.zip"
            title="Download Android source and GitHub workflow (.zip)"
            aria-label="Download Android source and GitHub workflow"
            className="min-h-[38px] min-w-[38px] px-2.5 rounded-md text-xs font-medium whitespace-nowrap shrink-0 flex items-center gap-1.5 transition-colors"
            style={{ color: theme.chromeMuted }}
          >
            <Download className="w-4 h-4" />
            <span className="hidden lg:inline">Android ZIP</span>
          </a>

          <button
            type="button"
            onClick={() => setSettingsOpen(true)}
            title="Terminal settings (Ctrl+,)"
            aria-label="Terminal settings"
            className="min-h-[38px] min-w-[38px] px-2.5 rounded-md text-xs font-medium whitespace-nowrap shrink-0 flex items-center gap-1.5 transition-colors cursor-pointer"
            style={{ color: theme.chromeText }}
          >
            <Settings className="w-4 h-4" />
            <span className="hidden sm:inline">Settings</span>
          </button>
        </div>
      </header>

      {/* Main Single-Tab Persistent Terminal Canvas */}
      <main className="flex-1 min-h-0 flex flex-col relative">
        <TerminalViewport
          ref={terminalRef}
          settings={settings}
          theme={theme}
          session={session}
          ctrlArmed={ctrlArmed}
          altArmed={altArmed}
          onConsumeModifiers={handleConsumeModifiers}
          onSessionChange={setSession}
          onFontComputed={setComputedFont}
          onSelectionChange={setHasSelection}
          onRestartRequested={handleRestartService}
          onZoomStep={handleZoomStep}
        />

        {session?.status === 'exited' && (
          <div
            className="shrink-0 border-t px-4 py-2.5 flex items-center justify-between gap-4"
            style={{
              backgroundColor: theme.chromeSurface,
              borderColor: theme.chromeBorder,
            }}
          >
            <div className="text-xs font-mono tabular-nums truncate" style={{ color: theme.chromeMuted }}>
              Service stopped · exit code {session.exitCode ?? 0}
            </div>
            <button
              type="button"
              onClick={handleRestartService}
              className="min-h-[40px] px-4 rounded-md text-xs font-medium whitespace-nowrap shrink-0 flex items-center gap-1.5 cursor-pointer"
              style={{
                backgroundColor: theme.accent,
                color: theme.accentText,
              }}
            >
              <RotateCcw className="w-3.5 h-3.5" />
              <span>Start New Session</span>
            </button>
          </div>
        )}
      </main>

      {/* Touch / Mobile Extra Keys Toolbar */}
      {showExtraKeys && session?.status !== 'exited' && (
        <ExtraKeysBar
          theme={theme}
          ctrlArmed={ctrlArmed}
          altArmed={altArmed}
          onToggleCtrl={() => setCtrlArmed((prev) => !prev)}
          onToggleAlt={() => setAltArmed((prev) => !prev)}
          onSendSequence={(seq) => terminalRef.current?.sendRawInput(seq)}
        />
      )}

      {/* Terminal Settings Slide-over */}
      <SettingsDrawer
        open={settingsOpen}
        settings={settings}
        theme={theme}
        computedFont={computedFont}
        session={session}
        onClose={() => {
          setSettingsOpen(false);
          terminalRef.current?.focus();
        }}
        onUpdate={updateSetting}
        onResetDefaults={resetSettings}
        onSendSigint={handleSendSigint}
        onRestartService={handleRestartService}
      />
    </div>
  );
}
