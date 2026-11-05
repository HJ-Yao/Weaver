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

/** 一个可进入的子目录。 */
export interface WorkspaceEntryDto {
  name: string;
  path: string;
}

/** 目录浏览结果。 */
export interface WorkspaceDirectoryDto {
  /** 当前目录的绝对路径。 */
  path: string;
  /** 上一级目录；已经在根时为 null。 */
  parent: string | null;
  /** 直接子目录，按名称排序。 */
  directories: WorkspaceEntryDto[];
  /** 文件系统根（Windows 盘符 / Unix 的 /）。 */
  roots: string[];
}

export const workspaceApi = {
  /** GET /workspace/diff?sessionId=：读取该会话工作区的未提交改动。 */
  diff: (sessionId?: string) =>
    request<WorkspaceDiffDto>(
      sessionId ? `/workspace/diff?sessionId=${encodeURIComponent(sessionId)}` : '/workspace/diff',
    ),

  /** GET /workspace/browse：列出目录的直接子目录；省略 path 时从用户主目录开始。 */
  browse: (path?: string) =>
    request<WorkspaceDirectoryDto>(
      path ? `/workspace/browse?path=${encodeURIComponent(path)}` : '/workspace/browse',
    ),
};
