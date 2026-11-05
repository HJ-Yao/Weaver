package cn.yhjyhj.weaver.server.store;

import cn.yhjyhj.weaver.server.api.dto.SessionSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 会话元数据存储契约。
 *
 * <p>标题允许为空，以支持后续自动命名；读取方负责把空标题映射为可展示标题。
 * 列表始终按最近更新时间倒序返回。
 */
public interface SessionStore {

    /** 写入新会话元数据。 */
    void create(String id, String title, Instant createdAt);

    /** 按最近更新时间倒序列出全部会话。 */
    List<SessionSummary> list();

    /** 查找会话元数据。 */
    Optional<SessionSummary> find(String id);

    /** 修改标题；会话不存在时返回 false。 */
    boolean rename(String id, String title);

    /** 更新会话工作目录；不存在时返回 false。 */
    boolean updateWorkspace(String id, String workspace);

    /** 仅在标题为空时写入；用于自动命名，不覆盖用户手动改名。 */
    boolean renameIfUnnamed(String id, String title);

    /** 刷新最近更新时间；会话不存在时忽略。 */
    void touch(String id);

    /** 删除元数据；会话不存在时返回 false。 */
    boolean delete(String id);

    /** 为已有事件但缺少元数据的会话补一行（标题为空）。 */
    int backfill(Instant createdAt);
}
