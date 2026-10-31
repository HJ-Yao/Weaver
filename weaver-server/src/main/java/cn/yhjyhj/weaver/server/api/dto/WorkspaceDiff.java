package cn.yhjyhj.weaver.server.api.dto;

import java.util.List;

/**
 * 工作区当前未提交改动的结构化快照。
 *
 * @param files 按 git diff 输出顺序排列的文件改动
 * @param truncated 是否因安全上限省略了后续文件或行
 */
public record WorkspaceDiff(List<FileDiff> files, boolean truncated) {

    /**
     * 单个文件的统计与可见 diff 行。
     *
     * @param path 相对于工作区根目录的文件路径
     * @param additions 返回行中的新增行数
     * @param deletions 返回行中的删除行数
     * @param lines 保留原始前缀的 diff 行
     */
    public record FileDiff(String path, int additions, int deletions, List<DiffLine> lines) {}

    /**
     * 一行统一 diff 内容。
     *
     * @param kind 行类型，只能为 {@code context}、{@code add} 或 {@code del}
     * @param text 包含原始 {@code +}、{@code -} 或空格前缀的整行文本
     */
    public record DiffLine(String kind, String text) {}
}
