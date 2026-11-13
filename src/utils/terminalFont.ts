import type { ComputedFontInfo, MonoFontId } from '../types/terminal';

export interface MonoFontOption {
  id: MonoFontId;
  label: string;
  stack: string;
}

export const MONO_FONT_OPTIONS: MonoFontOption[] = [
  {
    id: 'jetbrains',
    label: 'JetBrains Mono',
    stack: '"JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
  },
  {
    id: 'ibm-plex',
    label: 'IBM Plex Mono',
    stack: '"IBM Plex Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
  },
  {
    id: 'fira-code',
    label: 'Fira Code',
    stack: '"Fira Code", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
  },
  {
    id: 'roboto-mono',
    label: 'Roboto Mono',
    stack: '"Roboto Mono", ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
  },
  {
    id: 'system',
    label: 'System Monospace',
    stack: 'ui-monospace, SFMono-Regular, "Droid Sans Mono", Menlo, Monaco, Consolas, "Liberation Mono", monospace',
  },
];

export function getMonoFontStack(fontId: MonoFontId): string {
  return MONO_FONT_OPTIONS.find((item) => item.id === fontId)?.stack ?? MONO_FONT_OPTIONS[0].stack;
}

export function roundToHalf(value: number): number {
  return Math.round(value * 2) / 2;
}

export function computeAutoFontSize(params: {
  containerWidth: number;
  viewportWidth: number;
  viewportHeight: number;
  devicePixelRatio: number;
  isCoarsePointer: boolean;
  fontOffset: number;
}): ComputedFontInfo {
  const { containerWidth, viewportWidth, viewportHeight, devicePixelRatio, isCoarsePointer, fontOffset } = params;
  const effectiveWidth = Math.max(240, containerWidth || viewportWidth);

  let baseSize = 14.5;
  let deviceProfile = 'Desktop';
  let targetColsHint = 100;

  if (effectiveWidth < 520 || (isCoarsePointer && viewportWidth < 640)) {
    const isLandscape = viewportWidth > viewportHeight;
    deviceProfile = isLandscape ? 'Mobile Landscape' : 'Mobile Touch';
    // Target ~46-54 readable columns while guaranteeing >= 12.5px for legibility
    targetColsHint = isLandscape ? 64 : 48;
    const raw = effectiveWidth / (targetColsHint * 0.61);
    baseSize = Math.max(12.5, Math.min(14.5, raw));
  } else if (effectiveWidth < 960) {
    deviceProfile = isCoarsePointer ? 'Tablet Touch' : 'Compact Window';
    targetColsHint = 84;
    const raw = effectiveWidth / (targetColsHint * 0.61);
    baseSize = Math.max(13.5, Math.min(15.0, raw));
  } else if (effectiveWidth < 1440) {
    deviceProfile = devicePixelRatio >= 2 ? 'HiDPI Laptop' : 'Laptop / Desktop';
    targetColsHint = 112;
    baseSize = devicePixelRatio >= 2 ? 14.5 : 14.0;
  } else {
    deviceProfile = devicePixelRatio >= 2 ? 'HiDPI Display' : 'Wide Desktop';
    targetColsHint = 132;
    baseSize = effectiveWidth >= 1800 ? 16.0 : 15.0;
    if (devicePixelRatio >= 2) {
      baseSize += 0.5;
    }
  }

  const finalSize = Math.max(10, Math.min(26, roundToHalf(baseSize + fontOffset)));

  return {
    fontSize: finalSize,
    deviceProfile,
    targetColsHint,
  };
}
