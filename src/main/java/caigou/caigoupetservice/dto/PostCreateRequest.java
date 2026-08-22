package caigou.caigoupetservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 创建/编辑帖子请求体(编辑复用)
 * 请求体字段 snake_case,用 @JsonProperty 映射到 camelCase 字段
 */
@Data
public class PostCreateRequest {

    /** 标题 */
    private String title;
    /** 正文(必填) */
    private String content;
    /** 内容类型 */
    @JsonProperty("content_type")
    private Integer contentType;
    /** 摘要 */
    private String summary;
    /** 封面图URL */
    @JsonProperty("cover_url")
    private String coverUrl;
    /** 标签数组 */
    private List<String> tags;
    /** 状态:0=草稿 1=公开(默认) */
    private Integer status;
    /** 可见性:1公开 2仅粉丝 3仅好友 4仅自己(默认公开) */
    private Integer visibility;
}
