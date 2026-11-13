import type { ITheme } from '@xterm/xterm';

export type TerminalThemeId =
  | 'android-dark'
  | 'oled-black'
  | 'solarized-dark'
  | 'solarized-light'
  | 'green-phosphor'
  | 'amber-crt';

export type MonoFontId =
  | 'jetbrains'
  | 'ibm-plex'
  | 'fira-code'
  | 'roboto-mono'
  | 'system';

export type CursorStyle = 'block' | 'underline' | 'bar';
export type ExtraKeysMode = 'auto' | 'always' | 'never';
export type BellMode = 'visual' | 'audio' | 'off';
export type BackspaceMode = 'del' | 'ctrl-h';

export interface TerminalTheme {
  id: TerminalThemeId;
  name: string;
  isLight: boolean;
  chromeBg: string;
  chromeSurface: string;
  chromeBorder: string;
  chromeText: string;
  chromeMuted: string;
  accent: string;
  accentText: string;
  xterm: ITheme;
}

export interface TerminalSettings {
  autoFontSize: boolean;
  manualFontSize: number;
  autoFontOffset: number;
  fontFamily: MonoFontId;
  lineHeight: number;
  boldBright: boolean;
  themeId: TerminalThemeId;
  cursorStyle: CursorStyle;
  cursorBlink: boolean;
  scrollback: number;
  copyOnSelect: boolean;
  backspaceMode: BackspaceMode;
  bellMode: BellMode;
  extraKeysMode: ExtraKeysMode;
}

export interface SessionSnapshot {
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

export interface ComputedFontInfo {
  fontSize: number;
  deviceProfile: string;
  targetColsHint: number;
}

export const DEFAULT_TERMINAL_SETTINGS: TerminalSettings = {
  autoFontSize: true,
  manualFontSize: 14.5,
  autoFontOffset: 0,
  fontFamily: 'jetbrains',
  lineHeight: 1.25,
  boldBright: true,
  themeId: 'android-dark',
  cursorStyle: 'block',
  cursorBlink: true,
  scrollback: 5000,
  copyOnSelect: false,
  backspaceMode: 'del',
  bellMode: 'visual',
  extraKeysMode: 'auto',
};
