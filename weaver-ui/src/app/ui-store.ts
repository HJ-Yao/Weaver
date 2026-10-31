import { create } from 'zustand';
import type { DiffFile } from '@/entities/diff/model';

/**
 * Shell-wide UI state.
 *
 * This store only holds *view* state: theme, panel collapse, the selected
 * context tab, the open diff and the transient toast. Conversation facts live
 * in `entities/session/store.ts`; keeping them apart stops UI toggles from
 * re-rendering the transcript.
 */

export type Theme = 'dark' | 'light';

export type Accent =
  | 'violet'
  | 'blue'
  | 'teal'
  | 'green'
  | 'amber'
  | 'coral'
  | 'pink'
  | 'graphite';

/** Tabs of the right-hand context panel. */
export type ContextTab = 'tools' | 'approvals' | 'diff' | 'replay';

/** Approval mode mirrored from the runtime; TODO(M2): persist on the server. */
export type ApprovalMode = 'DEFAULT' | 'ACCEPT_EDITS' | 'PLAN';

const APPROVAL_MODES: ApprovalMode[] = ['DEFAULT', 'ACCEPT_EDITS', 'PLAN'];

interface UiState {
  theme: Theme;
  accent: Accent;
  leftCollapsed: boolean;
  rightCollapsed: boolean;
  contextTab: ContextTab;
  /** Current approval mode, shown in the composer and the status bar. */
  approvalMode: ApprovalMode;
  /** File shown in the overlay viewer, or `null` when it is closed. */
  openDiff: DiffFile | null;
  /** Toast text, or `null` when nothing is showing. */
  toast: string | null;

  setTheme: (theme: Theme) => void;
  setAccent: (accent: Accent) => void;
  toggleLeft: () => void;
  toggleRight: () => void;
  setContextTab: (tab: ContextTab) => void;
  cycleApprovalMode: () => void;
  openDiffViewer: (file: DiffFile) => void;
  closeDiffViewer: () => void;
  showToast: (message: string) => void;
  dismissToast: () => void;
}

const THEME_KEY = 'weaver.theme';
const ACCENT_KEY = 'weaver.accent';

/** Read a preference without letting a blocked storage API crash the app. */
function readPreference(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

function writePreference(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Private mode: preferences simply do not persist.
  }
}

const initialTheme: Theme = readPreference(THEME_KEY) === 'light' ? 'light' : 'dark';
const initialAccent = (readPreference(ACCENT_KEY) ?? 'violet') as Accent;

/** Push the theme onto `<html>` so tokens apply to the whole document. */
function applyTheme(theme: Theme, accent: Accent): void {
  document.documentElement.dataset.theme = theme;
  document.documentElement.dataset.accent = accent;
}

applyTheme(initialTheme, initialAccent);

export const useUiStore = create<UiState>()((set) => ({
  theme: initialTheme,
  accent: initialAccent,
  leftCollapsed: false,
  rightCollapsed: false,
  contextTab: 'tools',
  approvalMode: 'DEFAULT',
  openDiff: null,
  toast: null,

  setTheme: (theme) => {
    applyTheme(theme, useUiStore.getState().accent);
    writePreference(THEME_KEY, theme);
    set({ theme });
  },

  setAccent: (accent) => {
    applyTheme(useUiStore.getState().theme, accent);
    writePreference(ACCENT_KEY, accent);
    set({ accent });
  },

  toggleLeft: () => set((state) => ({ leftCollapsed: !state.leftCollapsed })),
  toggleRight: () => set((state) => ({ rightCollapsed: !state.rightCollapsed })),
  setContextTab: (contextTab) => set({ contextTab }),

  cycleApprovalMode: () =>
    set((state) => {
      const index = APPROVAL_MODES.indexOf(state.approvalMode);
      return { approvalMode: APPROVAL_MODES[(index + 1) % APPROVAL_MODES.length] };
    }),

  openDiffViewer: (openDiff) => set({ openDiff }),
  closeDiffViewer: () => set({ openDiff: null }),

  showToast: (toast) => set({ toast }),
  dismissToast: () => set({ toast: null }),
}));
