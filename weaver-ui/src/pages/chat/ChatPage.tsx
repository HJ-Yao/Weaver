import { Composer } from '@/features/composer/Composer';
import { DiffViewer } from '@/features/diff-viewer/DiffViewer';
import { MessageStream } from '@/features/message-stream/MessageStream';
import { ChatHeader } from './ChatHeader';
import styles from './ChatPage.module.css';

/**
 * Conversation screen.
 *
 * Three stacked regions: header (auto), transcript (fills, scrolls) and
 * composer (auto). The diff viewer is an overlay inside this section so it
 * covers the conversation but never the rail or the context panel.
 */
export function ChatPage() {
  return (
    <section className={styles.chat} aria-label="对话">
      <ChatHeader />
      <MessageStream />
      <Composer />
      <DiffViewer />
    </section>
  );
}
