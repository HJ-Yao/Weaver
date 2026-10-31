import { create } from 'zustand';
import type { DiffFile } from '@/entities/diff/model';

/**
 * 外壳全局 UI 状态。
 *
 * 此 store 只保存视图状态：主题、面板折叠、当前上下文标签页、打开的 diff
 * 和临时 toast。会话事实位于 `entities/session/store.ts`；两者分离可避免
 * UI 开关触发 transcript 重渲染。
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

/** 右侧上下文面板的标签页。 */
export type ContextTab = 'tools' | 'approvals' | 'diff' | 'replay';

/** 与运行时保持一致的审批模式；TODO(M2): 持久化到服务端。 */
export type ApprovalMode = 'DEFAULT' | 'ACCEPT_EDITS' | 'PLAN';

const APPROVAL_MODES: ApprovalMode[] = ['DEFAULT', 'ACCEPT_EDITS', 'PLAN'];

interface UiState {
  theme: Theme;
  accent: Accent;
  leftCollapsed: boolean;
  rightCollapsed: boolean;
  contextTab: ContextTab;
  /** 当前审批模式，显示在输入区和状态栏中。 */
  approvalMode: ApprovalMode;
  /** 浮层查看器中显示的文件；关闭时为 `null`。 */
  openDiff: DiffFile | null;
  /** toast 文本；没有内容显示时为 `null`。 */
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

/** 读取偏好设置，同时避免被阻止的存储 API 导致应用崩溃。 */
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
    // 隐私模式下偏好设置不会持久化。
  }
}

const initialTheme: Theme = readPreference(THEME_KEY) === 'light' ? 'light' : 'dark';
const initialAccent = (readPreference(ACCENT_KEY) ?? 'violet') as Accent;

/** 将主题写入 `<html>`，使 token 作用于整个文档。 */
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
