package caigou.caigoupetservice.dto;

import caigou.caigoupetservice.entity.Plugin;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 插件视图:返回给前端的插件信息,字段 snake_case 与 Express 响应一致
 * author 内嵌作者信息(替代 author_id);isFavorited 仅详情接口计算,列表恒 false
 * isFavorited 用 Boolean 包装类型:Lombok 生成 getIsFavorited(),Jackson 序列化键名恰为 isFavorited
 * (若用原始 boolean,Lombok 生成 isFavorited() getter,Jackson 会剥离 is 前缀序列化为 favorited)
 */
@Data
@AllArgsConstructor
public class PluginView {

    /** 主键ID */
    private Long id;

    /** 插件名称 */
    private String name;

    /** 版本号 */
    private String version;

    /** 插件描述 */
    private String description;

    /** 作者信息(内嵌 UserView,替代 author_id) */
    private UserView author;

    /** 分类 */
    private String category;

    /** 标签(逗号分隔) */
    private String tags;

    /** 图标URL */
    private String icon;

    /** 下载数 */
    private Integer download_count;

    /** 收藏数 */
    private Integer favorite_count;

    /** 插件清单JSON字符串 */
    private String manifest_json;

    /** 插件文件路径 */
    private String file_path;

    /** 文件字节数 */
    private Integer file_size;

    /** 状态:0=待审 1=通过 2=拒绝 */
    private Integer status;

    /** 审核意见 */
    private String review_comment;

    /** 创建时间 */
    private String created_at;

    /** 更新时间 */
    private String updated_at;

    /** 当前用户是否已收藏(未登录恒 false) */
    private Boolean isFavorited;

    /** 从实体构造视图 */
    public static PluginView from(Plugin p, UserView author, boolean isFavorited) {
        return new PluginView(p.getId(), p.getName(), p.getVersion(), p.getDescription(), author,
                p.getCategory(), p.getTags(), p.getIcon(), p.getDownloadCount(), p.getFavoriteCount(),
                p.getManifestJson(), p.getFilePath(), p.getFileSize(), p.getStatus(), p.getReviewComment(),
                p.getCreatedAt(), p.getUpdatedAt(), isFavorited);
    }
}
