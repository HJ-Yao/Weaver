import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { useServerStatusStore } from '@/app/server-status-store';
import { useUiStore } from '@/app/ui-store';
import { PROTOCOLS } from '@/entities/model-config/model';
import {
  settingsApi,
  type ModelSettingsDto,
  type ModelViewDto,
  type SaveModelRequest,
} from '@/shared/api/settings';
import styles from './SettingsPage.module.css';

type LoadState = 'loading' | 'ready' | 'error';

type DialogState =
  | { mode: 'create' }
  | { mode: 'edit'; model: ModelViewDto };

/**
 * 模型 API 设置。
 *
 * 列表与服务端脱敏配置保持同步；新增配置会立即激活，环境配置只允许启用。
 * API Key 仅随保存请求提交，服务端响应不会返回密钥原文。
 */
export function ModelSettings() {
  const [settings, setSettings] = useState<ModelSettingsDto | null>(null);
  const [loadState, setLoadState] = useState<LoadState>('loading');
  const [loadError, setLoadError] = useState<string | null>(null);
  const [dialogState, setDialogState] = useState<DialogState | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [confirmingDeleteId, setConfirmingDeleteId] = useState<string | null>(null);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const deleteTimerRef = useRef<number | null>(null);
  const refresh = useServerStatusStore((state) => state.refresh);
  const showToast = useUiStore((state) => state.showToast);

  const load = useCallback(async () => {
    setLoadError(null);
    setLoadState((current) => (current === 'ready' ? current : 'loading'));
    try {
      setSettings(await settingsApi.list());
      setLoadState('ready');
    } catch (error) {
      setLoadError(readableError(error));
      setLoadState('error');
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;

    if (dialogState) {
      dialog.showModal();
    } else if (dialog.open) {
      dialog.close();
    }
  }, [dialogState]);

  useEffect(
    () => () => {
      if (deleteTimerRef.current !== null) {
        window.clearTimeout(deleteTimerRef.current);
      }
    },
    [],
  );

  const openDialog = (next: DialogState) => {
    setFormError(null);
    setDialogState(next);
  };

  const closeDialog = () => {
    setFormError(null);
    setDialogState(null);
  };

  const activateModel = async (id: string) => {
    setPendingAction(`activate:${id}`);
    try {
      await settingsApi.activate(id);
      await load();
      await refresh();
      showToast('模型已启用');
    } catch (error) {
      showToast(`启用模型失败：${readableError(error)}`);
    } finally {
      setPendingAction(null);
    }
  };

  const requestDelete = async (id: string) => {
    if (confirmingDeleteId !== id) {
      if (deleteTimerRef.current !== null) {
        window.clearTimeout(deleteTimerRef.current);
      }
      setConfirmingDeleteId(id);
      deleteTimerRef.current = window.setTimeout(() => {
        setConfirmingDeleteId(null);
        deleteTimerRef.current = null;
      }, 3_000);
      return;
    }

    if (deleteTimerRef.current !== null) {
      window.clearTimeout(deleteTimerRef.current);
      deleteTimerRef.current = null;
    }
    setConfirmingDeleteId(null);
    setPendingAction(`delete:${id}`);
    try {
      await settingsApi.remove(id);
      await load();
      showToast('模型配置已删除');
    } catch (error) {
      showToast(`删除模型失败：${readableError(error)}`);
    } finally {
      setPendingAction(null);
    }
  };

  const submitForm = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!dialogState) return;

    const data = new FormData(event.currentTarget);
    const request: SaveModelRequest = {
      name: String(data.get('name') ?? '').trim(),
      protocol: String(data.get('protocol') ?? PROTOCOLS[0].value),
      baseUrl: String(data.get('baseUrl') ?? '').trim(),
      model: String(data.get('model') ?? '').trim(),
    };
    const apiKey = String(data.get('apiKey') ?? '').trim();
    if (apiKey) request.apiKey = apiKey;

    setFormError(null);
    setPendingAction('submit');
    try {
      if (dialogState.mode === 'create') {
        const created = await settingsApi.create(request);
        await settingsApi.activate(created.id);
      } else {
        await settingsApi.update(dialogState.model.id, request);
      }
      closeDialog();
      await load();
      await refresh();
      showToast(dialogState.mode === 'create' ? '模型已添加并启用' : '模型配置已保存');
    } catch (error) {
      setFormError(readableError(error));
    } finally {
      setPendingAction(null);
    }
  };

  const models = settings?.models ?? [];
  const isBusy = pendingAction !== null;

  return (
    <div className={styles.panel}>
      <div className={styles.panelHead}>
        <span>模型配置</span>
        <span className={styles.tiny}>{loadState === 'ready' ? `${models.length} 套` : ''}</span>
      </div>

      {loadState === 'loading' && (
        <p className={styles.tiny} role="status">
          正在加载模型配置…
        </p>
      )}

      {loadState === 'error' && (
        <div role="alert">
          <p className={styles.tiny}>模型配置加载失败：{loadError}</p>
          <button type="button" className={styles.smallButton} onClick={() => void load()}>
            重试
          </button>
        </div>
      )}

      {loadState === 'ready' && models.length === 0 && (
        <p className={styles.tiny}>暂无模型配置。</p>
      )}

      {loadState === 'ready' && models.length > 0 && (
        <div className={styles.configList} role="list" aria-label="模型配置列表">
          {models.map((model) => {
            const isActive = model.id === settings?.activeId;
            const canDelete = model.source === 'settings' && !isActive;
            return (
              <div
                key={model.id}
                role="listitem"
                className={`${styles.config} ${isActive ? styles.configActive : ''}`}
              >
                <div>
                  <div className={styles.configName}>{model.name}</div>
                  <div className={styles.configMeta}>
                    {model.protocol} · {model.model}
                  </div>
                </div>
                <div className={styles.keys}>
                  {isActive && <span className={styles.badge}>当前</span>}
                  {model.source === 'env' && <span className={styles.badge}>环境</span>}
                  {!model.hasKey && <mark>未配置密钥</mark>}
                  {!isActive && (
                    <button
                      type="button"
                      className={styles.smallButton}
                      disabled={isBusy}
                      onClick={() => void activateModel(model.id)}
                    >
                      启用
                    </button>
                  )}
                  {model.source === 'settings' && (
                    <button
                      type="button"
                      className={styles.smallButton}
                      disabled={isBusy}
                      onClick={() => openDialog({ mode: 'edit', model })}
                    >
                      编辑
                    </button>
                  )}
                  {canDelete && (
                    <button
                      type="button"
                      className={styles.smallButton}
                      disabled={isBusy}
                      onClick={() => void requestDelete(model.id)}
                    >
                      {confirmingDeleteId === model.id ? '确认删除' : '删除'}
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {loadState === 'ready' && (
        <button
          type="button"
          className={styles.addButton}
          onClick={() => openDialog({ mode: 'create' })}
        >
          ＋ 添加配置
        </button>
      )}

      <dialog
        ref={dialogRef}
        className={styles.dialog}
        aria-label={dialogState?.mode === 'edit' ? '编辑模型配置' : '添加模型配置'}
        onCancel={(event) => {
          event.preventDefault();
          closeDialog();
        }}
      >
        {dialogState && (
          <form
            key={dialogState.mode === 'edit' ? dialogState.model.id : 'create'}
            className={styles.form}
            onSubmit={(event) => void submitForm(event)}
          >
            <div className={styles.dialogHead}>
              <h2 className={styles.dialogTitle}>
                {dialogState.mode === 'edit' ? '编辑模型配置' : '添加模型配置'}
              </h2>
              <button
                type="button"
                className={styles.closeButton}
                aria-label="关闭"
                onClick={closeDialog}
              >
                ×
              </button>
            </div>

            <label className={styles.field}>
              <span>名称</span>
              <input
                name="name"
                defaultValue={dialogState.mode === 'edit' ? dialogState.model.name : ''}
                placeholder="例如 DeepSeek Chat"
                required
                autoFocus
              />
            </label>

            <label className={styles.field}>
              <span>协议</span>
              <select
                name="protocol"
                defaultValue={
                  dialogState.mode === 'edit'
                    ? dialogState.model.protocol
                    : PROTOCOLS[0].value
                }
              >
                {PROTOCOLS.map((protocol) => (
                  <option key={protocol.value} value={protocol.value}>
                    {protocol.label}
                  </option>
                ))}
              </select>
            </label>

            <label className={styles.field}>
              <span>Base URL</span>
              <input
                name="baseUrl"
                defaultValue={dialogState.mode === 'edit' ? dialogState.model.baseUrl : ''}
                placeholder="https://api.deepseek.com"
                spellCheck={false}
                required
              />
            </label>

            <label className={styles.field}>
              <span>模型</span>
              <input
                name="model"
                defaultValue={dialogState.mode === 'edit' ? dialogState.model.model : ''}
                placeholder="deepseek-chat"
                spellCheck={false}
                required
              />
            </label>

            <label className={styles.field}>
              <span>API Key</span>
              <input
                name="apiKey"
                type="password"
                aria-label="API Key"
                placeholder={
                  dialogState.mode === 'edit' ? '留空则保持原密钥' : 'sk-...'
                }
                autoComplete="off"
              />
              <span className={styles.tiny}>
                {dialogState.mode === 'edit'
                  ? '留空会沿用现有密钥；若尚未配置，模型调用将失败。'
                  : '可留空保存，但未配置密钥时模型调用将失败。'}
              </span>
            </label>

            {formError && <p role="alert">保存失败：{formError}</p>}

            <div className={styles.foot}>
              <span className={styles.tiny}>配置保存在 ~/.weaver/config.json</span>
              <button type="submit" className={styles.primaryButton} disabled={isBusy}>
                {isBusy && pendingAction === 'submit' ? '保存中…' : '保存'}
              </button>
            </div>
          </form>
        )}
      </dialog>
    </div>
  );
}

/** 从未知异常中提取可展示信息，同时避免空消息。 */
function readableError(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  return message || '未知错误';
}
