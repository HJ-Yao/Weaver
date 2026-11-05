import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { useServerStatusStore } from '@/app/server-status-store';
import { useUiStore } from '@/app/ui-store';
import { useSessionStore } from '@/entities/session/store';
import type { InboxTargetDto } from '@/shared/api/sessions';
import { Icon } from '@/shared/ui/Icon';
import { WorkspacePicker } from '@/features/workspace-picker/WorkspacePicker';
import styles from './Composer.module.css';
import { QueuedMessages } from './QueuedMessages';

/**
 * 消息输入区。
 *
 * 仅持有草稿和工作区选择；当前模型从服务端状态 store 只读获取。
 * 发送动作委托给会话 store，因此输入区无需感知模拟流还是真实流。
 */
export function Composer() {
  const sendMessage = useSessionStore((state) => state.sendMessage);
  const enqueueMessage = useSessionStore((state) => state.enqueueMessage);
  const removePending = useSessionStore((state) => state.removePending);
  const editPending = useSessionStore((state) => state.editPending);
  const pending = useSessionStore((state) => state.pending);
  const cancelRun = useSessionStore((state) => state.cancelRun);
  const setWorkspace = useSessionStore((state) => state.setWorkspace);
  const running = useSessionStore((state) => state.running);
  const activeSessionId = useSessionStore((state) => state.activeSessionId);
  const sessions = useSessionStore((state) => state.sessions);
  const approvalMode = useUiStore((state) => state.approvalMode);
  const cycleApprovalMode = useUiStore((state) => state.cycleApprovalMode);
  const showToast = useUiStore((state) => state.showToast);
  const serverStatus = useServerStatusStore((state) => state.status);

  const [draft, setDraft] = useState('');
  const [workspacePickerOpen, setWorkspacePickerOpen] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const activeWorkspace = sessions.find((session) => session.id === activeSessionId)?.workspace;

  // 随内容增高，达到 CSS 最大高度后改为滚动。
  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = 'auto';
    textarea.style.height = `${Math.min(textarea.scrollHeight, 180)}px`;
  }, [draft]);

  const queueMessage = async (target: InboxTargetDto) => {
    const content = draft;
    if (!content.trim()) return;

    const accepted = await enqueueMessage(content, target);
    if (!accepted) {
      showToast('投递失败：运行可能已结束或待发送队列已满。');
      return;
    }
    // 请求期间若用户继续输入，只清空已经投递出去的那份文本。
    setDraft((current) => (current === content ? '' : current));
  };

  const submit = () => {
    if (!draft.trim()) return;
    if (running) {
      void queueMessage('TURN');
      return;
    }
    sendMessage(draft);
    setDraft('');
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key !== 'Enter' || event.shiftKey) return;
    // IME 候选窗口打开时不得发送。
    if (event.nativeEvent.isComposing) return;
    event.preventDefault();
    if (running && (event.ctrlKey || event.metaKey)) {
      void queueMessage('STEP');
      return;
    }
    submit();
  };

  const selectWorkspace = async (path: string) => {
    setWorkspacePickerOpen(false);
    if (!(await setWorkspace(path))) {
      showToast('切换工作目录失败');
    }
  };

  return (
    <div className={styles.composer}>
      <div className={styles.tools}>
        <button
          type="button"
          className={styles.workspaceButton}
          aria-label="选择工作目录"
          title={running ? '运行中不能切换工作目录' : activeWorkspace}
          disabled={running}
          onClick={() => setWorkspacePickerOpen(true)}
        >
          <Icon name="folder" size={13} />
          <span className={styles.workspaceName}>{workspaceLabel(activeWorkspace)}</span>
          <Icon name="chevron-down" size={11} />
        </button>
        <span className={styles.spacer} />

        <button
          type="button"
          className={styles.iconButton}
          title="添加图片或文件"
          aria-label="添加图片或文件"
          onClick={() => showToast('图片与文件上传将在后续版本接入。')}
        >
          <Icon name="paperclip" size={15} />
        </button>
        <button
          type="button"
          className={styles.iconButton}
          title="命令"
          aria-label="命令"
          onClick={() => {
            setDraft((current) => (current.startsWith('/') ? current : `/${current}`));
            textareaRef.current?.focus();
          }}
        >
          /
        </button>
        <button
          type="button"
          className={styles.chip}
          title="切换审批模式"
          onClick={cycleApprovalMode}
        >
          审批：{approvalMode}
        </button>
      </div>

      <QueuedMessages entries={pending} onRemove={removePending} onEdit={editPending} />

      <textarea
        ref={textareaRef}
        className={styles.input}
        rows={2}
        value={draft}
        placeholder="描述任务，或输入 / 查看命令"
        aria-label="消息输入框"
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={onKeyDown}
      />

      <div className={styles.actions}>
        <span className={styles.hint}>
          {running
            ? 'Enter 排队 · Ctrl+Enter 插话 · Shift+Enter 换行'
            : 'Enter 发送 · Shift+Enter 换行'}
        </span>
        <span className={styles.right}>
          {/* TODO(M2): 多模型配置接入后恢复为下拉；当前只展示服务端正在使用的模型。 */}
          <span className={styles.modelLabel} title={serverStatus?.protocol}>
            {serverStatus?.model ?? '—'}
          </span>
          {running ? (
            <span className={styles.actionGroup}>
              <button
                type="button"
                className={styles.send}
                disabled={!draft.trim()}
                onClick={() => void queueMessage('STEP')}
              >
                插话
              </button>
              <button
                type="button"
                className={styles.secondaryAction}
                disabled={!draft.trim()}
                onClick={() => void queueMessage('TURN')}
              >
                排队
              </button>
              <button type="button" className={styles.stop} onClick={cancelRun}>
                停止
              </button>
            </span>
          ) : (
            <button type="button" className={styles.send} disabled={!draft.trim()} onClick={submit}>
              发送
            </button>
          )}
        </span>
      </div>
      <WorkspacePicker
        open={workspacePickerOpen}
        onSelect={(path) => void selectWorkspace(path)}
        onClose={() => setWorkspacePickerOpen(false)}
      />
    </div>
  );
}

/** 从服务端绝对路径中提取用于按钮展示的最后一段。 */
function workspaceLabel(path: string | undefined): string {
  if (!path) return '选择工作目录';
  const withoutTrailingSeparators = path.replace(/[\\/]+$/, '');
  if (!withoutTrailingSeparators) return path;
  return withoutTrailingSeparators.split(/[\\/]/).at(-1) || path;
}
