package cn.yhjyhj.weaver.server.api.dto;

import java.util.List;

/**
 * 单个目录的浏览结果。
 *
 * @param path 当前目录绝对路径
 * @param parent 父目录绝对路径；已位于文件系统根时为 null
 * @param directories 按名称不区分大小写排序的子目录
 * @param roots 当前文件系统可见的根目录
 */
public record WorkspaceDirectory(
        String path, String parent, List<WorkspaceEntry> directories, List<String> roots) {

    /** 固定列表快照，避免响应构造后仍被外部修改。 */
    public WorkspaceDirectory {
        directories = List.copyOf(directories);
        roots = List.copyOf(roots);
    }
}
