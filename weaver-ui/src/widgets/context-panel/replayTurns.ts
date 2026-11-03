import type { SessionEventDto } from '@/shared/api/sessions';

/** 按用户消息划出的一轮事件；首轮之前的孤儿事件使用空轮次。 */
export interface ReplayTurnGroup {
  /** 从 1 开始的轮次；准备组为 null。 */
  turnNumber: number | null;
  /** 当前轮的用户消息；准备组为 null。 */
  userMessage: SessionEventDto | null;
  /** 属于当前轮的事件，保持原始 seq 顺序。 */
  events: SessionEventDto[];
}

/**
 * 将事件按用户消息切分为轮次。
 *
 * <p>第一条用户消息之前的事件归入准备组；没有用户消息时也只产生准备组。
 * 该函数不修改输入数组，输出中的事件对象保持原引用。
 *
 * @param events 按 seq 升序排列的事件
 * @returns 准备组（如有）后接各用户轮次
 */
export function groupTurns(events: SessionEventDto[]): ReplayTurnGroup[] {
  const groups: ReplayTurnGroup[] = [];
  const preparedEvents: SessionEventDto[] = [];
  let currentGroup: ReplayTurnGroup | null = null;
  let turnNumber = 0;

  for (const event of events) {
    if (event.type === 'USER_MESSAGE') {
      currentGroup = {
        turnNumber: ++turnNumber,
        userMessage: event,
        events: [event],
      };
      groups.push(currentGroup);
    } else if (currentGroup) {
      currentGroup.events.push(event);
    } else {
      preparedEvents.push(event);
    }
  }

  if (preparedEvents.length > 0) {
    groups.unshift({
      turnNumber: null,
      userMessage: null,
      events: preparedEvents,
    });
  }

  return groups;
}
