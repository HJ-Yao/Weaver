import { useRef, useState, type FormEvent } from 'react';
import {
  DEMO_MODEL_CONFIGS,
  PROTOCOLS,
  type ModelConfig,
  type ModelProtocol,
} from '@/entities/model-config/model';
import styles from './SettingsPage.module.css';

/**
 * Model connection profiles.
 *
 * Profiles are local state until the settings API exists — see TODO(M2) in
 * `entities/model-config/model.ts`. The API key field is deliberately never
 * stored here: keys belong to the server process, not the browser.
 */
export function ModelSettings() {
  const [configs, setConfigs] = useState<ModelConfig[]>(DEMO_MODEL_CONFIGS);
  const dialogRef = useRef<HTMLDialogElement>(null);

  const openDialog = () => dialogRef.current?.showModal();
  const closeDialog = () => dialogRef.current?.close();

  const activate = (id: string) => {
    setConfigs((current) => current.map((config) => ({ ...config, active: config.id === id })));
  };

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);

    const name = String(data.get('name') ?? '').trim() || '未命名配置';
    const protocol = String(data.get('protocol') ?? 'openai-chat-completions') as ModelProtocol;
    const baseUrl = String(data.get('baseUrl') ?? '').trim();
    const model = String(data.get('model') ?? '').trim();

    setConfigs((current) => [
      ...current,
      { id: `config-${Date.now()}`, name, protocol, baseUrl, model, active: false },
    ]);
    event.currentTarget.reset();
    closeDialog();
  };

  return (
    <div className={styles.panel}>
      <div className={styles.panelHead}>
        <span>模型配置</span>
        <span className={styles.tiny}>{configs.length} 套</span>
      </div>

      <div className={styles.configList}>
        {configs.map((config) => (
          <div key={config.id} className={`${styles.config} ${config.active ? styles.configActive : ''}`}>
            <div>
              <div className={styles.configName}>{config.name}</div>
              <div className={styles.configMeta}>
                {config.protocol} · {config.model || config.baseUrl || '未填写'}
              </div>
            </div>
            {config.active ? (
              <span className={styles.badge}>当前</span>
            ) : (
              <button type="button" className={styles.smallButton} onClick={() => activate(config.id)}>
                启用
              </button>
            )}
          </div>
        ))}
      </div>

      <button type="button" className={styles.addButton} onClick={openDialog}>
        ＋ 添加配置
      </button>

      <dialog ref={dialogRef} className={styles.dialog} aria-label="添加模型配置">
        <form className={styles.form} onSubmit={onSubmit}>
          <div className={styles.dialogHead}>
            <h2 className={styles.dialogTitle}>添加模型配置</h2>
            <button type="button" className={styles.closeButton} aria-label="关闭" onClick={closeDialog}>
              ×
            </button>
          </div>

          <label className={styles.field}>
            <span>名称</span>
            <input name="name" placeholder="例如 DeepSeek Chat" autoFocus />
          </label>

          <label className={styles.field}>
            <span>协议</span>
            <select name="protocol" defaultValue={PROTOCOLS[0].value}>
              {PROTOCOLS.map((protocol) => (
                <option key={protocol.value} value={protocol.value}>
                  {protocol.label}
                </option>
              ))}
            </select>
          </label>

          <label className={styles.field}>
            <span>Base URL</span>
            <input name="baseUrl" placeholder="https://api.deepseek.com" spellCheck={false} />
          </label>

          <label className={styles.field}>
            <span>模型</span>
            <input name="model" placeholder="deepseek-chat" spellCheck={false} />
          </label>

          <label className={styles.field}>
            <span>API Key</span>
            <input name="apiKey" type="password" placeholder="sk-..." autoComplete="off" />
          </label>

          <div className={styles.foot}>
            <span className={styles.tiny}>配置保存在 ~/.weaver/config.json</span>
            <button type="submit" className={styles.primaryButton}>
              保存
            </button>
          </div>
        </form>
      </dialog>
    </div>
  );
}
