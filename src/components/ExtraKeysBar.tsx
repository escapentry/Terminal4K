import type { TerminalTheme } from '../types/terminal';

interface ExtraKeysBarProps {
  theme: TerminalTheme;
  ctrlArmed: boolean;
  altArmed: boolean;
  onToggleCtrl: () => void;
  onToggleAlt: () => void;
  onSendSequence: (sequence: string) => void;
}

interface KeyDef {
  label: string;
  ariaLabel: string;
  sequence?: string;
  action?: 'ctrl' | 'alt';
}

const EXTRA_KEYS: KeyDef[] = [
  { label: 'ESC', ariaLabel: 'Escape key', sequence: '\x1b' },
  { label: 'TAB', ariaLabel: 'Tab key', sequence: '\t' },
  { label: 'CTRL', ariaLabel: 'Control modifier', action: 'ctrl' },
  { label: 'ALT', ariaLabel: 'Alt modifier', action: 'alt' },
  { label: '↑', ariaLabel: 'Up arrow', sequence: '\x1b[A' },
  { label: '↓', ariaLabel: 'Down arrow', sequence: '\x1b[B' },
  { label: '←', ariaLabel: 'Left arrow', sequence: '\x1b[D' },
  { label: '→', ariaLabel: 'Right arrow', sequence: '\x1b[C' },
  { label: '/', ariaLabel: 'Forward slash', sequence: '/' },
  { label: '-', ariaLabel: 'Hyphen', sequence: '-' },
  { label: '|', ariaLabel: 'Pipe', sequence: '|' },
  { label: '~', ariaLabel: 'Tilde', sequence: '~' },
  { label: 'HOME', ariaLabel: 'Home key', sequence: '\x1b[H' },
  { label: 'END', ariaLabel: 'End key', sequence: '\x1b[F' },
];

export function ExtraKeysBar({
  theme,
  ctrlArmed,
  altArmed,
  onToggleCtrl,
  onToggleAlt,
  onSendSequence,
}: ExtraKeysBarProps) {
  return (
    <div
      className="shrink-0 border-t px-2 py-1.5 grid grid-cols-5 sm:grid-cols-7 gap-1.5 select-none"
      style={{
        backgroundColor: theme.chromeSurface,
        borderColor: theme.chromeBorder,
      }}
      role="toolbar"
      aria-label="Terminal extra keys"
    >
      {EXTRA_KEYS.map((key) => {
        const isArmed = (key.action === 'ctrl' && ctrlArmed) || (key.action === 'alt' && altArmed);
        return (
          <button
            key={key.label}
            type="button"
            aria-label={key.ariaLabel}
            aria-pressed={key.action ? isArmed : undefined}
            onPointerDown={(e) => {
              e.preventDefault();
              if (key.action === 'ctrl') {
                onToggleCtrl();
              } else if (key.action === 'alt') {
                onToggleAlt();
              } else if (key.sequence) {
                onSendSequence(key.sequence);
              }
            }}
            className="min-h-[36px] px-1.5 rounded-md font-mono text-xs font-medium whitespace-nowrap flex items-center justify-center transition-colors focus-visible:outline-2 focus-visible:outline-offset-1 cursor-pointer"
            style={{
              backgroundColor: isArmed ? theme.accent : theme.chromeBg,
              color: isArmed ? theme.accentText : theme.chromeText,
              border: `1px solid ${isArmed ? theme.accent : theme.chromeBorder}`,
              outlineColor: theme.accent,
            }}
          >
            {key.label}
          </button>
        );
      })}
    </div>
  );
}
