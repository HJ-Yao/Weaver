import type { FileDiffDto } from '@/shared/api/workspace';

/** 一个工作区变更文件及其后端返回的统一 diff。 */
export type DiffFile = FileDiffDto;

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
