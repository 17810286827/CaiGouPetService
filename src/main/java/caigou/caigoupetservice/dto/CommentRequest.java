package caigou.caigoupetservice.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * 创建评论请求体(发一级评论或二级回复共用)
 * 请求体字段 snake_case,用 @JsonProperty 映射到 camelCase 字段
 */
@Data
public class CommentRequest {

    /** 所属帖子ID(必填) */
    @JsonProperty("post_id")
    private Long postId;
    /** 评论内容(必填) */
    private String content;
    /** 父评论ID(可空,一级评论不传) */
    @JsonProperty("parent_id")
    private Long parentId;
}
