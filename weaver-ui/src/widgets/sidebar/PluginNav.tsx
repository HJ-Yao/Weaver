import { useSearchParams } from 'react-router-dom';
import styles from './Sidebar.module.css';

/** 静态目录；TODO(M2): 从 MCP 注册表和技能索引加载。 */
const PLUGINS = [
  { id: 'agentic-git', name: 'agentic-git', meta: 'stdio · 12 个工具', status: 'ok' as const },
  { id: 'agentic-shell', name: 'agentic-shell', meta: 'stdio · 8 个工具', status: 'ok' as const },
  { id: 'github', name: 'github', meta: 'stdio · 连接中', status: 'running' as const },
];

const SKILLS = [
  { id: 'tdd', name: 'test-driven-development', meta: '内置 · 先红后绿' },
  { id: 'review', name: 'code-review-and-quality', meta: '内置 · 多轴审查' },
  { id: 'source', name: 'source-driven-development', meta: '内置 · 以官方文档为准' },
];

/**
 * 插件侧栏。
 *
 * 当前标签页保存在 URL（`/plugins?tab=skill`）中，使侧栏和页面正文不会脱节，
 * 且视图可直接链接。
 */
export function PluginNav() {
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') === 'skill' ? 'skill' : 'mcp';

  return (
    <>
      <div className={styles.top}>
        <span className={styles.label}>自定义</span>
      </div>

      <div className={styles.navList}>
        <button
          type="button"
          className={`${styles.navItem} ${tab === 'mcp' ? styles.navItemActive : ''}`}
          aria-pressed={tab === 'mcp'}
          onClick={() => setParams({ tab: 'mcp' })}
        >
          <span>插件</span>
          <span className={styles.tiny}>MCP</span>
        </button>
        <button
          type="button"
          className={`${styles.navItem} ${tab === 'skill' ? styles.navItemActive : ''}`}
          aria-pressed={tab === 'skill'}
          onClick={() => setParams({ tab: 'skill' })}
        >
          <span>技能</span>
          <span className={styles.tiny}>Skill</span>
        </button>
      </div>

      <div className={styles.panelHead}>
        <span>已安装</span>
        <span className={styles.tiny}>{tab === 'mcp' ? PLUGINS.length : SKILLS.length}</span>
      </div>

      <div className={styles.list}>
        {tab === 'mcp'
          ? PLUGINS.map((plugin) => (
              <div key={plugin.id} className={styles.item}>
                <span
                  className={`${styles.dot} ${plugin.status === 'ok' ? styles.ok : styles.running}`}
                  aria-hidden="true"
                />
                <div>
                  <div className={styles.itemName}>{plugin.name}</div>
                  <div className={styles.itemMeta}>{plugin.meta}</div>
                </div>
              </div>
            ))
          : SKILLS.map((skill) => (
              <div key={skill.id} className={styles.item}>
                <span className={`${styles.dot} ${styles.ok}`} aria-hidden="true" />
                <div>
                  <div className={styles.itemName}>{skill.name}</div>
                  <div className={styles.itemMeta}>{skill.meta}</div>
                </div>
              </div>
            ))}
      </div>
    </>
  );
}
