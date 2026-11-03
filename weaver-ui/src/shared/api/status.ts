import { request } from './http';

/** `cn.yhjyhj.weaver.server.api.dto.ServerStatus` */
export interface ServerStatusDto {
  status: string;
  storage: string;
  wal: boolean;
  workspace: string;
  maxSteps: number;
  uptimeSeconds: number;
  /** 当前配置的模型名。 */
  model: string;
  /** 当前模型协议标识。 */
  protocol: string;
}

export const statusApi = {
  /** GET /status：服务健康与运行信息。 */
  get: () => request<ServerStatusDto>('/status'),
};
