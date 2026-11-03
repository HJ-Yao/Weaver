import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { useServerStatusStore } from '@/app/server-status-store';
import { useUiStore } from '@/app/ui-store';
import { useSessionStore } from '@/entities/session/store';
import { Dropdown, type DropdownOption } from '@/shared/ui/Dropdown';
import { Icon } from '@/shared/ui/Icon';
import styles from './Composer.module.css';

/**
 * TODO(M2): 这些目录项应来自服务端（工作区注册表和 `~/.weaver/config.json`）。
 * 当前硬编码仅为在这些接口就绪前保持外壳可用。
 */
const WORKSPACES: DropdownOption[] = [
  { id: 'weaver', label: 'Weaver', meta: 'F:\\Back-End\\Project\\PrivateProjects\\Weaver' },
  {
    id: 'ai-agent-scaffold',
    label: 'ai-agent-scaffold',
    meta: 'F:\\Back-End\\Project\\PrivateProjects\\AI-Agent-Scaffold-self\\ai-agent-scaffold',
  },
  {
    id: 'deepseek-harness-java',
    label: 'deepseek-harness-java',
    meta: 'F:\\Back-End\\Project\\PrivateProjects\\DeepSeek-Harness-Java\\deepseek-harness-java',
  },
];

/**
 * 消息输入区。
 *
 * 仅持有草稿和工作区选择；当前模型从服务端状态 store 只读获取。
 * 发送动作委托给会话 store，因此输入区无需感知模拟流还是真实流。
 */
export function Composer() {
  const sendMessage = useSessionStore((state) => state.sendMessage);
  const cancelRun = useSessionStore((state) => state.cancelRun);
  const running = useSessionStore((state) => state.running);
  const approvalMode = useUiStore((state) => state.approvalMode);
  const cycleApprovalMode = useUiStore((state) => state.cycleApprovalMode);
  const showToast = useUiStore((state) => state.showToast);
  const serverStatus = useServerStatusStore((state) => state.status);

  const [draft, setDraft] = useState('');
  const [workspace, setWorkspace] = useState(WORKSPACES[0].id);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // 随内容增高，达到 CSS 最大高度后改为滚动。
  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = 'auto';
    textarea.style.height = `${Math.min(textarea.scrollHeight, 180)}px`;
  }, [draft]);

  const submit = () => {
    if (!draft.trim() || running) return;
    sendMessage(draft);
    setDraft('');
  };

  const onPrimaryAction = () => {
    if (running) {
      cancelRun();
      return;
    }
    submit();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key !== 'Enter' || event.shiftKey) return;
    // IME 候选窗口打开时不得发送。
    if (event.nativeEvent.isComposing) return;
    event.preventDefault();
    submit();
  };

  return (
    <div className={styles.composer}>
      <div className={styles.tools}>
        <Dropdown
          label="选择工作区"
          options={WORKSPACES}
          value={workspace}
          onChange={setWorkspace}
          placement="up"
        />
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
        <span className={styles.hint}>Enter 发送 · Shift+Enter 换行</span>
        <span className={styles.right}>
          {/* TODO(M2): 多模型配置接入后恢复为下拉；当前只展示服务端正在使用的模型。 */}
          <span className={styles.modelLabel} title={serverStatus?.protocol}>
            {serverStatus?.model ?? '—'}
          </span>
          <button
            type="button"
            className={styles.send}
            disabled={!running && !draft.trim()}
            onClick={onPrimaryAction}
          >
            {running ? '停止' : '发送'}
          </button>
        </span>
      </div>
    </div>
  );
}
