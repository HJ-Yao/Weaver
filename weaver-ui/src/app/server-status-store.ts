import { create } from 'zustand';
import type { ServerStatusDto } from '@/shared/api/status';

/** 服务端连接阶段。 */
export type ServerStatusPhase = 'connecting' | 'connected' | 'disconnected';

/**
 * 服务端状态在状态栏、输入区等视图间的共享事实。
 *
 * `status` 只在连接成功时存在，断开后立即清空，避免 UI 展示过期模型信息。
 */
interface ServerStatusState {
  /** 当前连接阶段。 */
  phase: ServerStatusPhase;
  /** 最近一次成功获取的服务端状态。 */
  status: ServerStatusDto | null;
  /** 最近一次连接失败的可读信息。 */
  error: string | null;
  /** 写入成功状态并清除旧错误。 */
  setConnected: (status: ServerStatusDto) => void;
  /** 写入断开状态并清除过期服务端详情。 */
  setDisconnected: (error: string) => void;
  /** 开始新的连接尝试。 */
  setConnecting: () => void;
}

export const useServerStatusStore = create<ServerStatusState>()((set) => ({
  phase: 'connecting',
  status: null,
  error: null,

  setConnected: (status) => set({ phase: 'connected', status, error: null }),
  setDisconnected: (error) => set({ phase: 'disconnected', status: null, error }),
  setConnecting: () => set({ phase: 'connecting', status: null, error: null }),
}));
