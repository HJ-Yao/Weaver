import { request } from './http';

/** `cn.yhjyhj.weaver.server.api.dto.ServerStatus` */
export interface ServerStatusDto {
  status: string;
  storage: string;
  wal: boolean;
  workspace: string;
  maxSteps: number;
  uptimeSeconds: number;
}

export const statusApi = {
  /** GET /status：服务健康与运行信息。 */
  get: () => request<ServerStatusDto>('/status'),
};
