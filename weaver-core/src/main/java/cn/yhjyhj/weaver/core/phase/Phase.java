package cn.yhjyhj.weaver.core.phase;

/**
 * 一次运行的生命周期状态机。
 *
 * sealed 保证状态集合封闭：新增状态必须显式修改这里，编译器会替我们查漏。
 * 状态迁移方法只挂在"允许出发的"状态类型上——非法迁移在编译期就被禁止，
 * 而不是留到运行时抛异常。
 */
public sealed interface Phase permits Phase.Idle, Phase.Running, Phase.Maintenance {

    /** 当前轮次（从 0 起），跨状态持续累计。 */
    int turn();

    /** 当前轮内已完成步数。 */
    int step();

    /** 收件箱有待处理消息时由外部置位。 */
    boolean wakeRequested();

    /** 是否已被中止（只有 Maintenance 为 true）。 */
    boolean aborted();

    /** 新会话的初始状态。 */
    static Idle idle() {
        return new Idle(0, 0, false);
    }

    /** 空闲：无运行，等待唤醒或外部指令。 */
    record Idle(int turn, int step, boolean wakeRequested) implements Phase {
        @Override
        public boolean aborted() {
            return false;
        }

        /** 唤醒标记：收到新消息时置位，供循环拾取。 */
        public Idle wake() {
            return new Idle(turn, step, true);
        }

        /** 启动一轮运行。 */
        public Running start() {
            return new Running(turn, 0, false);
        }
    }

    /** 运行中：主循环逐步推进。 */
    record Running(int turn, int step, boolean wakeRequested) implements Phase {
        @Override
        public boolean aborted() {
            return false;
        }

        /** 完成一步：步数 +1，轮次不变。 */
        public Running nextStep() {
            return new Running(turn, step + 1, wakeRequested);
        }

        /** 进入下一轮：轮次 +1，步数归零。 */
        public Running nextTurn() {
            return new Running(turn + 1, 0, wakeRequested);
        }

        /** 正常运行结束，回到空闲，保留已完成的轮次。 */
        public Idle complete() {
            return new Idle(turn, 0, false);
        }

        /** 中止：进入维护态，保留现场计数以便审计与恢复。 */
        public Maintenance abort() {
            return new Maintenance(turn, step);
        }

        /** 运行中收到新消息：置唤醒位，待本轮结束后处理。 */
        public Running wake() {
            return new Running(turn, step, true);
        }
    }

    /** 维护态：收尾/清理/中止后的落脚点。 */
    record Maintenance(int turn, int step) implements Phase {
        @Override
        public boolean wakeRequested() {
            return false;
        }

        @Override
        public boolean aborted() {
            return true;
        }
    }
}