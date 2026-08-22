package caigou.caigoupetservice.dto;

import caigou.caigoupetservice.entity.Post;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 帖子视图:返回给前端,含作者 user 内嵌,字段 snake_case
 */
@Data
@AllArgsConstructor
public class PostView {

    /** 主键ID */
    private Long id;
    /** 作者ID */
    private Long user_id;
    /** 标题 */
    private String title;
    /** 正文 */
    private String content;
    /** 内容类型 */
    private Integer content_type;
    /** 摘要 */
    private String summary;
    /** 封面图URL */
    private String cover_url;
    /** 标签数组 */
    private List<String> tags;
    /** 状态 */
    private Integer status;
    /** 可见性:1公开 2仅粉丝 3仅好友 4仅自己 */
    private Integer visibility;
    /** 浏览数 */
    private Integer view_count;
    /** 点赞数 */
    private Integer like_count;
    /** 评论数 */
    private Integer comment_count;
    /** 是否置顶 */
    private Integer is_top;
    /** 创建时间 */
    private String created_at;
    /** 更新时间 */
    private String updated_at;
    /** 作者信息 */
    private UserView user;

    /** 从实体构造视图(tags 由 JSON 字符串解析为数组,字段顺序与构造器一致) */
    public static PostView from(Post p, UserView author) {
        return new PostView(p.getId(), p.getUserId(), p.getTitle(), p.getContent(), p.getContentType(),
                p.getSummary(), p.getCoverUrl(), parseTags(p.getTags()), p.getStatus(), p.getVisibility(),
                p.getViewCount(), p.getLikeCount(), p.getCommentCount(), p.getIsTop(),
                p.getCreatedAt(), p.getUpdatedAt(), author);
    }

    /** 解析 tags JSON 数组字符串;空/非法返回空列表 */
    private static List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            ObjectMapper om = new ObjectMapper();
            return om.readValue(tagsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
