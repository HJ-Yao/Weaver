package cn.yhjyhj.weaver.core.tool;

/** 操作的可回滚程度；审批策略据此降级或升级风险。 */
public enum Reversibility {
    /** 只读或可完全撤销，无副作用。 */
    REVERSIBLE,

    /** 有副作用，但在工作区内可经 git 快照恢复。 */
    RECOVERABLE,

    /** 不可回滚：删除、远端推送、装依赖、工作区外写入。 */
    IRREVERSIBLE
}
