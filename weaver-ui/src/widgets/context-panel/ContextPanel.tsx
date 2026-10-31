import { useShallow } from 'zustand/react/shallow';
import { useUiStore, type ContextTab } from '@/app/ui-store';
import { useSessionStore } from '@/entities/session/store';
import { ApprovalsPanel } from './ApprovalsPanel';
import { DiffPanel } from './DiffPanel';
import { ReplayPanel } from './ReplayPanel';
import { ToolsPanel } from './ToolsPanel';
import styles from './ContextPanel.module.css';

const TABS: { id: ContextTab; label: string }[] = [
  { id: 'tools', label: '工具' },
  { id: 'approvals', label: '审批' },
  { id: 'diff', label: 'Diff' },
  { id: 'replay', label: '回放' },
];

/**
 * 右侧上下文面板。
 *
 * 当前标签页保存在 UI store 中，其他界面（例如页头的“回放”操作）
 * 无需持有此面板即可打开指定标签页。
 */
export function ContextPanel() {
  const activeTab = useUiStore((state) => state.contextTab);
  const setContextTab = useUiStore((state) => state.setContextTab);
  const toggleRight = useUiStore((state) => state.toggleRight);

  const pendingApprovals = useSessionStore(
    useShallow((state) =>
      state.transcript.filter((item) => item.kind === 'approval' && item.status === 'pending'),
    ),
  ).length;

  return (
    <aside className={styles.panel} aria-label="上下文面板">
      <div className={styles.tabs} role="tablist" aria-label="上下文视图">
        {TABS.map((tab) => (
          <button
            key={tab.id}
            type="button"
            role="tab"
            id={`context-tab-${tab.id}`}
            aria-selected={activeTab === tab.id}
            aria-controls={`context-panel-${tab.id}`}
            className={`${styles.tab} ${activeTab === tab.id ? styles.tabActive : ''}`}
            onClick={() => setContextTab(tab.id)}
          >
            {tab.label}
            {tab.id === 'approvals' && pendingApprovals > 0 && (
              <span className={styles.badge}>{pendingApprovals}</span>
            )}
          </button>
        ))}

        <button
          type="button"
          className={styles.collapse}
          title="收起上下文栏"
          aria-label="收起上下文栏"
          onClick={toggleRight}
        >
          »
        </button>
      </div>

      <div
        className={styles.body}
        role="tabpanel"
        id={`context-panel-${activeTab}`}
        aria-labelledby={`context-tab-${activeTab}`}
      >
        {activeTab === 'tools' && <ToolsPanel />}
        {activeTab === 'approvals' && <ApprovalsPanel />}
        {activeTab === 'diff' && <DiffPanel />}
        {activeTab === 'replay' && <ReplayPanel />}
      </div>
    </aside>
  );
}
