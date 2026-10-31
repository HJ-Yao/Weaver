/**
 * 工作区 diff 模型。
 *
 * TODO(M2): 服务端提供工作区变更后，用 `GET /sessions/{id}/diff` 的响应
 * 替换 `demoDiffFiles`。
 */

/** 统一 diff 中渲染的一行。 */
export interface DiffLine {
  kind: 'context' | 'add' | 'del';
  text: string;
}

/** 一个变更文件及其预先计算好的统一 diff。 */
export interface DiffFile {
  path: string;
  additions: number;
  deletions: number;
  lines: DiffLine[];
}

/** 后端接口就绪前由上下文面板使用的演示数据。 */
export const demoDiffFiles: DiffFile[] = [
  {
    path: 'core/approval/RuleBasedApprovalPolicy.java',
    additions: 42,
    deletions: 18,
    lines: [
      { kind: 'del', text: '- ApprovalDecision base = firstMatch(call);' },
      { kind: 'add', text: '+ ApprovalResult base = baseDecision(call, reversibility);' },
      { kind: 'add', text: '+ base = applyMode(base, reversibility);' },
      { kind: 'add', text: '+ base = applyGrant(base, call, context);' },
      { kind: 'add', text: '+ return applyUnattended(base, context);' },
    ],
  },
  {
    path: 'core/events/SessionEvents.java',
    additions: 31,
    deletions: 12,
    lines: [],
  },
  {
    path: 'test/.../RuleBasedApprovalPolicyTest.java',
    additions: 24,
    deletions: 0,
    lines: [],
  },
];
