import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useUiStore } from '@/app/ui-store';
import styles from './PluginsPage.module.css';

/** TODO(M2): load from the MCP registry and the skill index. */
const PLUGINS = [
  { id: 'agentic-git', name: 'agentic-git', meta: 'stdio · 12 个工具', status: 'ok' },
  { id: 'agentic-shell', name: 'agentic-shell', meta: 'stdio · 8 个工具', status: 'ok' },
  { id: 'github', name: 'github', meta: 'stdio · 连接中', status: 'running' },
];

const SKILLS = [
  { id: 'tdd', name: 'test-driven-development', meta: '内置 · 先红后绿' },
  { id: 'review', name: 'code-review-and-quality', meta: '内置 · 多轴审查' },
  { id: 'source', name: 'source-driven-development', meta: '内置 · 以官方文档为准' },
];

type Scope = 'public' | 'personal';

/**
 * Plugin / skill catalogue.
 *
 * The tab comes from the URL (`?tab=mcp|skill`) so the sidebar selection and
 * this page always agree. Scope and search are still client-side; TODO(M2)
 * moves them to the catalogue endpoint.
 */
export function PluginsPage() {
  const [params] = useSearchParams();
  const tab = params.get('tab') === 'skill' ? 'skill' : 'mcp';
  const [scope, setScope] = useState<Scope>('public');
  const [query, setQuery] = useState('');
  const showToast = useUiStore((state) => state.showToast);

  const isPlugins = tab === 'mcp';
  const needle = query.trim().toLowerCase();
  const items = (isPlugins ? PLUGINS : SKILLS).filter((item) =>
    item.name.toLowerCase().includes(needle),
  );

  return (
    <section className={styles.page} aria-label={isPlugins ? '插件' : '技能'}>
      <header className={styles.head}>
        <div>
          <h1 className={styles.title}>{isPlugins ? '插件' : '技能'}</h1>
          <p className={styles.subtitle}>
            {isPlugins
              ? '连接插件，让 Weaver 在你的各类工具中协同工作。'
              : '启用技能，给 Agent 注入可复用的工作流。'}
          </p>
        </div>
        <button
          type="button"
          className={styles.addButton}
          onClick={() => showToast(isPlugins ? '插件安装将在后续版本接入。' : '技能安装将在后续版本接入。')}
        >
          ＋ 添加{isPlugins ? '插件' : '技能'}
        </button>
      </header>

      <div className={styles.toolbar}>
        <div className={styles.segmented} role="group" aria-label="范围">
          {(['public', 'personal'] as const).map((value) => (
            <button
              key={value}
              type="button"
              className={`${styles.seg} ${scope === value ? styles.segActive : ''}`}
              aria-pressed={scope === value}
              onClick={() => setScope(value)}
            >
              {value === 'public' ? '公开' : '个人'}
            </button>
          ))}
        </div>

        <input
          className={styles.search}
          type="search"
          value={query}
          placeholder={isPlugins ? '搜索插件' : '搜索技能'}
          aria-label={isPlugins ? '搜索插件' : '搜索技能'}
          onChange={(event) => setQuery(event.target.value)}
        />
      </div>

      <div className={styles.grid}>
        {items.map((item) => (
          <div key={item.id} className={styles.card}>
            {'status' in item && (
              <span
                className={`${styles.dot} ${item.status === 'ok' ? styles.ok : styles.running}`}
                aria-hidden="true"
              />
            )}
            <div>
              <div className={styles.name}>{item.name}</div>
              <div className={styles.meta}>{item.meta}</div>
            </div>
          </div>
        ))}

        {items.length === 0 && <p className={styles.empty}>没有匹配项。</p>}
      </div>
    </section>
  );
}
