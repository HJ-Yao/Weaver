import { request } from './http';

/** 工作区 diff 的行类型。 */
export type DiffLineKindDto = 'context' | 'add' | 'del';

/** `cn.yhjyhj.weaver.server.api.dto.WorkspaceDiff.DiffLine` */
export interface DiffLineDto {
  kind: DiffLineKindDto;
  text: string;
}

/** `cn.yhjyhj.weaver.server.api.dto.WorkspaceDiff.FileDiff` */
export interface FileDiffDto {
  path: string;
  additions: number;
  deletions: number;
  lines: DiffLineDto[];
}

/** `cn.yhjyhj.weaver.server.api.dto.WorkspaceDiff` */
export interface WorkspaceDiffDto {
  files: FileDiffDto[];
  truncated: boolean;
}

export const workspaceApi = {
  /** GET /workspace/diff：读取当前工作区的未提交改动。 */
  diff: () => request<WorkspaceDiffDto>('/workspace/diff'),
};
