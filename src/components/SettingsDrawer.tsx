import { useEffect, useState } from 'react';
import { Copy, Download, RotateCcw, Terminal, X } from 'lucide-react';
import type {
  BackspaceMode,
  BellMode,
  ComputedFontInfo,
  CursorStyle,
  ExtraKeysMode,
  SessionSnapshot,
  TerminalSettings,
  TerminalTheme,
} from '../types/terminal';
import { MONO_FONT_OPTIONS } from '../utils/terminalFont';
import { TERMINAL_THEME_LIST } from '../utils/terminalThemes';

interface SettingsDrawerProps {
  open: boolean;
  settings: TerminalSettings;
  theme: TerminalTheme;
  computedFont: ComputedFontInfo;
  session: SessionSnapshot | null;
  onClose: () => void;
  onUpdate: <K extends keyof TerminalSettings>(key: K, value: TerminalSettings[K]) => void;
  onResetDefaults: () => void;
  onSendSigint: () => void;
  onRestartService: () => void;
}

function formatDuration(startedAt: number, endedAt: number | null): string {
  if (!startedAt) return '0s';
  const end = endedAt ?? Date.now();
  const totalSec = Math.max(0, Math.floor((end - startedAt) / 1000));
  const hrs = Math.floor(totalSec / 3600);
  const mins = Math.floor((totalSec % 3600) / 60);
  const secs = totalSec % 60;
  if (hrs > 0) return `${hrs}h ${mins}m ${secs}s`;
  if (mins > 0) return `${mins}m ${secs}s`;
  return `${secs}s`;
}

export function SettingsDrawer({
  open,
  settings,
  theme,
  computedFont,
  session,
  onClose,
  onUpdate,
  onResetDefaults,
  onSendSigint,
  onRestartService,
}: SettingsDrawerProps) {
  const [, setTick] = useState(0);
  const [workflowCopied, setWorkflowCopied] = useState(false);

  const handleCopyWorkflow = async () => {
    try {
      const res = await fetch('/api/android/workflow');
      if (!res.ok) return;
      const yaml = await res.text();
      await navigator.clipboard.writeText(yaml);
      setWorkflowCopied(true);
      setTimeout(() => setWorkflowCopied(false), 1600);
    } catch {
      // Clipboard unavailable
    }
  };

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    const interval = setInterval(() => setTick((t) => t + 1), 1000);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      clearInterval(interval);
    };
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div
        className="fixed inset-0 bg-black/50 backdrop-blur-[2px]"
        onClick={onClose}
        aria-hidden="true"
      />
      <aside
        role="dialog"
        aria-modal="true"
        aria-label="Terminal Settings"
        className="relative z-10 h-full w-full sm:w-[420px] flex flex-col border-l overflow-hidden"
        style={{
          backgroundColor: theme.chromeSurface,
          borderColor: theme.chromeBorder,
          color: theme.chromeText,
        }}
      >
        {/* Drawer Header */}
        <div
          className="shrink-0 flex items-center justify-between gap-4 px-5 py-3.5 border-b"
          style={{ borderColor: theme.chromeBorder }}
        >
          <div className="flex items-center gap-2.5 min-w-0">
            <Terminal className="w-4 h-4 shrink-0" style={{ color: theme.accent }} />
            <h2 className="text-sm font-semibold tracking-tight truncate">Terminal Settings</h2>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close settings"
            className="min-h-[40px] min-w-[40px] rounded-md flex items-center justify-center transition-colors cursor-pointer"
            style={{ color: theme.chromeMuted }}
          >
            <X className="w-4 h-4" />
          </button>
        </div>

        {/* Scrollable Settings Body */}
        <div className="flex-1 overflow-y-auto px-5 py-5 space-y-7">
          {/* 1. Typography & Auto-Scaling */}
          <section className="space-y-4">
            <div className="flex items-baseline justify-between gap-2">
              <h3 className="text-xs font-semibold tracking-wide" style={{ color: theme.chromeMuted }}>
                Monospace & Auto-Scale
              </h3>
              <span className="font-mono tabular-nums text-xs" style={{ color: theme.accent }}>
                {computedFont.fontSize.toFixed(1)}px · {computedFont.deviceProfile}
              </span>
            </div>

            <div className="flex items-center justify-between gap-4">
              <div className="min-w-0">
                <div className="text-sm font-medium">Auto-adjust font per device</div>
                <div className="text-xs mt-0.5" style={{ color: theme.chromeMuted }}>
                  Scales readable monospace size from viewport width and DPR
                </div>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={settings.autoFontSize}
                onClick={() => onUpdate('autoFontSize', !settings.autoFontSize)}
                className="relative inline-flex h-7 w-12 shrink-0 items-center rounded-full transition-colors cursor-pointer"
                style={{
                  backgroundColor: settings.autoFontSize ? theme.accent : theme.chromeBorder,
                }}
              >
                <span
                  className="inline-block h-5 w-5 rounded-full bg-white transition-transform"
                  style={{
                    transform: settings.autoFontSize ? 'translateX(24px)' : 'translateX(4px)',
                  }}
                />
              </button>
            </div>

            {settings.autoFontSize ? (
              <div className="space-y-2">
                <div className="flex items-center justify-between text-xs">
                  <span style={{ color: theme.chromeMuted }}>Auto scale fine-tune</span>
                  <span className="font-mono tabular-nums">
                    {settings.autoFontOffset > 0 ? `+${settings.autoFontOffset}` : settings.autoFontOffset}px
                  </span>
                </div>
                <input
                  type="range"
                  min={-3}
                  max={6}
                  step={0.5}
                  value={settings.autoFontOffset}
                  onChange={(e) => onUpdate('autoFontOffset', Number(e.target.value))}
                  className="w-full cursor-pointer"
                  style={{ accentColor: theme.accent }}
                />
              </div>
            ) : (
              <div className="space-y-2">
                <div className="flex items-center justify-between text-xs">
                  <span style={{ color: theme.chromeMuted }}>Manual font size</span>
                  <span className="font-mono tabular-nums">{settings.manualFontSize.toFixed(1)}px</span>
                </div>
                <input
                  type="range"
                  min={10}
                  max={26}
                  step={0.5}
                  value={settings.manualFontSize}
                  onChange={(e) => onUpdate('manualFontSize', Number(e.target.value))}
                  className="w-full cursor-pointer"
                  style={{ accentColor: theme.accent }}
                />
              </div>
            )}

            <div className="space-y-2">
              <label className="block text-xs" style={{ color: theme.chromeMuted }}>
                Monospace typeface
              </label>
              <div className="grid grid-cols-1 gap-1.5">
                {MONO_FONT_OPTIONS.map((font) => {
                  const active = settings.fontFamily === font.id;
                  return (
                    <button
                      key={font.id}
                      type="button"
                      onClick={() => onUpdate('fontFamily', font.id)}
                      className="min-h-[40px] px-3 rounded-md flex items-center justify-between text-left text-sm transition-colors cursor-pointer"
                      style={{
                        fontFamily: font.stack,
                        backgroundColor: active ? theme.chromeBg : 'transparent',
                        border: `1px solid ${active ? theme.accent : theme.chromeBorder}`,
                      }}
                    >
                      <span>{font.label}</span>
                      <span className="text-xs opacity-70">0O1lI</span>
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="space-y-2">
              <div className="text-xs" style={{ color: theme.chromeMuted }}>Line height</div>
              <div className="grid grid-cols-3 gap-1.5">
                {[1.15, 1.25, 1.4].map((lh) => {
                  const active = settings.lineHeight === lh;
                  return (
                    <button
                      key={lh}
                      type="button"
                      onClick={() => onUpdate('lineHeight', lh)}
                      className="min-h-[40px] rounded-md font-mono tabular-nums text-xs font-medium cursor-pointer"
                      style={{
                        backgroundColor: active ? theme.accent : theme.chromeBg,
                        color: active ? theme.accentText : theme.chromeText,
                        border: `1px solid ${active ? theme.accent : theme.chromeBorder}`,
                      }}
                    >
                      {lh}
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="flex items-center justify-between gap-4">
              <span className="text-sm font-medium">Bright bold colors</span>
              <button
                type="button"
                role="switch"
                aria-checked={settings.boldBright}
                onClick={() => onUpdate('boldBright', !settings.boldBright)}
                className="relative inline-flex h-7 w-12 shrink-0 items-center rounded-full transition-colors cursor-pointer"
                style={{
                  backgroundColor: settings.boldBright ? theme.accent : theme.chromeBorder,
                }}
              >
                <span
                  className="inline-block h-5 w-5 rounded-full bg-white transition-transform"
                  style={{
                    transform: settings.boldBright ? 'translateX(24px)' : 'translateX(4px)',
                  }}
                />
              </button>
            </div>
          </section>

          <hr style={{ borderColor: theme.chromeBorder }} />

          {/* 2. Color Scheme & Cursor */}
          <section className="space-y-4">
            <h3 className="text-xs font-semibold tracking-wide" style={{ color: theme.chromeMuted }}>
              Color Scheme & Cursor
            </h3>

            <div className="grid grid-cols-1 gap-1.5">
              {TERMINAL_THEME_LIST.map((item) => {
                const active = settings.themeId === item.id;
                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => onUpdate('themeId', item.id)}
                    className="min-h-[42px] px-3 rounded-md flex items-center justify-between text-sm transition-colors cursor-pointer"
                    style={{
                      backgroundColor: active ? theme.chromeBg : 'transparent',
                      border: `1px solid ${active ? theme.accent : theme.chromeBorder}`,
                    }}
                  >
                    <span className="truncate">{item.name}</span>
                    <span className="flex items-center gap-1 shrink-0" aria-hidden="true">
                      <span
                        className="w-3.5 h-3.5 rounded-sm border"
                        style={{ backgroundColor: item.xterm.background, borderColor: theme.chromeBorder }}
                      />
                      <span className="w-3.5 h-3.5 rounded-sm" style={{ backgroundColor: item.xterm.foreground }} />
                      <span className="w-3.5 h-3.5 rounded-sm" style={{ backgroundColor: item.accent }} />
                    </span>
                  </button>
                );
              })}
            </div>

            <div className="space-y-2">
              <div className="text-xs" style={{ color: theme.chromeMuted }}>Cursor style</div>
              <div className="grid grid-cols-3 gap-1.5">
                {(['block', 'underline', 'bar'] as CursorStyle[]).map((style) => {
                  const active = settings.cursorStyle === style;
                  return (
                    <button
                      key={style}
                      type="button"
                      onClick={() => onUpdate('cursorStyle', style)}
                      className="min-h-[40px] rounded-md text-xs font-medium capitalize cursor-pointer"
                      style={{
                        backgroundColor: active ? theme.accent : theme.chromeBg,
                        color: active ? theme.accentText : theme.chromeText,
                        border: `1px solid ${active ? theme.accent : theme.chromeBorder}`,
                      }}
                    >
                      {style}
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="flex items-center justify-between gap-4">
              <span className="text-sm font-medium">Cursor blink</span>
              <button
                type="button"
                role="switch"
                aria-checked={settings.cursorBlink}
                onClick={() => onUpdate('cursorBlink', !settings.cursorBlink)}
                className="relative inline-flex h-7 w-12 shrink-0 items-center rounded-full transition-colors cursor-pointer"
                style={{
                  backgroundColor: settings.cursorBlink ? theme.accent : theme.chromeBorder,
                }}
              >
                <span
                  className="inline-block h-5 w-5 rounded-full bg-white transition-transform"
                  style={{
                    transform: settings.cursorBlink ? 'translateX(24px)' : 'translateX(4px)',
                  }}
                />
              </button>
            </div>
          </section>

          <hr style={{ borderColor: theme.chromeBorder }} />

          {/* 3. Keyboard, Input & Buffer */}
          <section className="space-y-4">
            <h3 className="text-xs font-semibold tracking-wide" style={{ color: theme.chromeMuted }}>
              Input & Buffer
            </h3>

            <div className="space-y-2">
              <div className="text-xs" style={{ color: theme.chromeMuted }}>Extra keys bar</div>
              <div className="grid grid-cols-3 gap-1.5">
                {([
                  { id: 'auto', label: 'Auto' },
                  { id: 'always', label: 'Show' },
                  { id: 'never', label: 'Hide' },
                ] as { id: ExtraKeysMode; label: string }[]).map((opt) => {
                  const active = settings.extraKeysMode === opt.id;
                  return (
                    <button
                      key={opt.id}
                      type="button"
                      onClick={() => onUpdate('extraKeysMode', opt.id)}
                      className="min-h-[40px] rounded-md text-xs font-medium cursor-pointer"
                      style={{
                        backgroundColor: active ? theme.accent : theme.chromeBg,
                        color: active ? theme.accentText : theme.chromeText,
                        border: `1px solid ${active ? theme.accent : theme.chromeBorder}`,
                      }}
                    >
                      {opt.label}
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="space-y-2">
              <div className="text-xs" style={{ color: theme.chromeMuted }}>Backspace key sequence</div>
              <div className="grid grid-cols-2 gap-1.5">
                {([
                  { id: 'del', label: 'DEL (0x7F)' },
                  { id: 'ctrl-h', label: 'Ctrl-H (0x08)' },
                ] as { id: BackspaceMode; label: string }[]).map((opt) => {
                  const active = settings.backspaceMode === opt.id;
                  return (
                    <button
                      key={opt.id}
                      type="button"
                      onClick={() => onUpdate('backspaceMode', opt.id)}
                      className="min-h-[40px] rounded-md font-mono text-xs font-medium cursor-pointer"
                      style={{
                        backgroundColor: active ? theme.accent : theme.chromeBg,
                        color: active ? theme.accentText : theme.chromeText,
                        border: `1px solid ${active ? theme.accent : theme.chromeBorder}`,
                      }}
                    >
                      {opt.label}
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="space-y-2">
              <div className="text-xs" style={{ color: theme.chromeMuted }}>Terminal bell</div>
              <div className="grid grid-cols-3 gap-1.5">
                {(['visual', 'audio', 'off'] as BellMode[]).map((mode) => {
                  const active = settings.bellMode === mode;
                  return (
                    <button
                      key={mode}
                      type="button"
                      onClick={() => onUpdate('bellMode', mode)}
                      className="min-h-[40px] rounded-md text-xs font-medium capitalize cursor-pointer"
                      style={{
                        backgroundColor: active ? theme.accent : theme.chromeBg,
                        color: active ? theme.accentText : theme.chromeText,
                        border: `1px solid ${active ? theme.accent : theme.chromeBorder}`,
                      }}
                    >
                      {mode}
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="space-y-2">
              <div className="text-xs" style={{ color: theme.chromeMuted }}>Scrollback lines</div>
              <div className="grid grid-cols-4 gap-1.5">
                {[1000, 3000, 5000, 10000].map((lines) => {
                  const active = settings.scrollback === lines;
                  return (
                    <button
                      key={lines}
                      type="button"
                      onClick={() => onUpdate('scrollback', lines)}
                      className="min-h-[40px] rounded-md font-mono tabular-nums text-xs font-medium cursor-pointer"
                      style={{
                        backgroundColor: active ? theme.accent : theme.chromeBg,
                        color: active ? theme.accentText : theme.chromeText,
                        border: `1px solid ${active ? theme.accent : theme.chromeBorder}`,
                      }}
                    >
                      {lines}
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="flex items-center justify-between gap-4">
              <span className="text-sm font-medium">Copy text on select</span>
              <button
                type="button"
                role="switch"
                aria-checked={settings.copyOnSelect}
                onClick={() => onUpdate('copyOnSelect', !settings.copyOnSelect)}
                className="relative inline-flex h-7 w-12 shrink-0 items-center rounded-full transition-colors cursor-pointer"
                style={{
                  backgroundColor: settings.copyOnSelect ? theme.accent : theme.chromeBorder,
                }}
              >
                <span
                  className="inline-block h-5 w-5 rounded-full bg-white transition-transform"
                  style={{
                    transform: settings.copyOnSelect ? 'translateX(24px)' : 'translateX(4px)',
                  }}
                />
              </button>
            </div>
          </section>

          <hr style={{ borderColor: theme.chromeBorder }} />

          {/* 4. Persistent Service Info & Controls */}
          <section className="space-y-3">
            <h3 className="text-xs font-semibold tracking-wide" style={{ color: theme.chromeMuted }}>
              Persistent Terminal Service
            </h3>

            <dl className="grid grid-cols-2 gap-y-2 text-xs font-mono tabular-nums">
              <dt style={{ color: theme.chromeMuted }}>Package</dt>
              <dd className="text-right truncate">com.android.terminal</dd>

              <dt style={{ color: theme.chromeMuted }}>Service Status</dt>
              <dd className="text-right">
                {session?.status === 'running'
                  ? 'Active until exit'
                  : session
                    ? `Exited (${session.exitCode ?? 0})`
                    : 'Connecting'}
              </dd>

              <dt style={{ color: theme.chromeMuted }}>Shell / PID</dt>
              <dd className="text-right">
                {session?.shell ?? '/bin/bash'} · {session?.pid ?? '—'}
              </dd>

              <dt style={{ color: theme.chromeMuted }}>Grid Size</dt>
              <dd className="text-right">
                {session ? `${session.cols} × ${session.rows}` : '—'}
              </dd>

              <dt style={{ color: theme.chromeMuted }}>Uptime</dt>
              <dd className="text-right">
                {session ? formatDuration(session.startedAt, session.endedAt) : '—'}
              </dd>
            </dl>

            <div className="pt-2 grid grid-cols-2 gap-2">
              <button
                type="button"
                disabled={session?.status !== 'running'}
                onClick={onSendSigint}
                className="min-h-[42px] px-3 rounded-md text-xs font-medium whitespace-nowrap disabled:opacity-40 cursor-pointer"
                style={{
                  backgroundColor: theme.chromeBg,
                  border: `1px solid ${theme.chromeBorder}`,
                  color: theme.chromeText,
                }}
              >
                Send SIGINT (^C)
              </button>
              <button
                type="button"
                onClick={onRestartService}
                className="min-h-[42px] px-3 rounded-md text-xs font-medium whitespace-nowrap flex items-center justify-center gap-1.5 cursor-pointer"
                style={{
                  backgroundColor: theme.accent,
                  color: theme.accentText,
                }}
              >
                <RotateCcw className="w-3.5 h-3.5" />
                <span>Restart Service</span>
              </button>
            </div>

            <button
              type="button"
              onClick={onResetDefaults}
              className="w-full min-h-[40px] px-3 rounded-md text-xs font-medium cursor-pointer"
              style={{
                backgroundColor: 'transparent',
                border: `1px solid ${theme.chromeBorder}`,
                color: theme.chromeMuted,
              }}
            >
              Reset settings to defaults
            </button>
          </section>

          <hr style={{ borderColor: theme.chromeBorder }} />

          {/* 5. Android APK & GitHub Actions Export */}
          <section className="space-y-3">
            <h3 className="text-xs font-semibold tracking-wide" style={{ color: theme.chromeMuted }}>
              Android Source & GitHub Workflow
            </h3>
            <p className="text-xs leading-relaxed" style={{ color: theme.chromeMuted }}>
              Includes the native Android project (<span className="font-mono">com.android.terminal</span>) and{' '}
              <span className="font-mono">.github/workflows/build-android.yml</span> for GitHub Actions APK builds.
            </p>
            <div className="grid grid-cols-1 gap-2">
              <a
                href="/api/android/download-source"
                download="Terminal-com.android.terminal-github.zip"
                className="min-h-[42px] px-3 rounded-md text-xs font-medium whitespace-nowrap flex items-center justify-center gap-2"
                style={{
                  backgroundColor: theme.accent,
                  color: theme.accentText,
                }}
              >
                <Download className="w-3.5 h-3.5" />
                <span>Download Android + Workflow (.zip)</span>
              </a>
              <button
                type="button"
                onClick={handleCopyWorkflow}
                className="min-h-[40px] px-3 rounded-md text-xs font-medium whitespace-nowrap flex items-center justify-center gap-2 cursor-pointer"
                style={{
                  backgroundColor: theme.chromeBg,
                  border: `1px solid ${theme.chromeBorder}`,
                  color: theme.chromeText,
                }}
              >
                <Copy className="w-3.5 h-3.5" />
                <span>{workflowCopied ? 'Copied build-android.yml' : 'Copy Workflow YAML'}</span>
              </button>
            </div>
          </section>
        </div>
      </aside>
    </div>
  );
}
