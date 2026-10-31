/**
 * Workspace diff model.
 *
 * TODO(M2): replace `demoDiffFiles` with a `GET /sessions/{id}/diff` response
 * once the server exposes workspace changes.
 */

/** One rendered line of a unified diff. */
export interface DiffLine {
  kind: 'context' | 'add' | 'del';
  text: string;
}

/** A changed file with its pre-computed unified diff. */
export interface DiffFile {
  path: string;
  additions: number;
  deletions: number;
  lines: DiffLine[];
}

/** Demo data used by the context panel until the backend endpoint exists. */
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
