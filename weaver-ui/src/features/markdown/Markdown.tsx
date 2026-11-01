import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import styles from './Markdown.module.css';

interface MarkdownProps {
  /** 原始 Markdown 文本。 */
  content: string;
}

const allowedLinkProtocols = new Set(['http:', 'https:', 'mailto:']);

const markdownComponents: Components = {
  a({ href, children }) {
    const safeHref = getSafeHref(href);
    return (
      <a
        href={safeHref}
        target={safeHref ? '_blank' : undefined}
        rel={safeHref ? 'noopener noreferrer' : undefined}
      >
        {children}
      </a>
    );
  },
  img({ src, alt, title }) {
    // 图片源仍由浏览器加载，渲染此处即可能向远程主机发起请求。
    return <img src={src} alt={alt} title={title} loading="lazy" />;
  },
  table({ children }) {
    return (
      <div className={styles.tableWrapper}>
        <table>{children}</table>
      </div>
    );
  },
};

/**
 * 把模型输出渲染为 Markdown；不解析原始 HTML。
 *
 * 生产渲染路径不接受原始 HTML，因此没有接入 `rehype-raw`。链接还经过
 * 协议白名单校验，避免 `javascript:` 等协议进入可点击地址。
 */
export function Markdown({ content }: MarkdownProps) {
  return (
    <div className={styles.markdown}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>
        {content}
      </ReactMarkdown>
    </div>
  );
}

function getSafeHref(href: string | undefined): string | undefined {
  if (!href) return undefined;

  try {
    return allowedLinkProtocols.has(new URL(href).protocol) ? href : undefined;
  } catch {
    return undefined;
  }
}
