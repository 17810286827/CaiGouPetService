package caigou.caigoupetservice.dto;

import caigou.caigoupetservice.entity.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 资源视图:返回给前端的资源信息,字段名与 Express 响应一致(snake_case)
 */
@Data
@AllArgsConstructor
public class ResourceView {

    /** 主键ID */
    private Long id;
    /** 上传者ID */
    private Long user_id;
    /** 类型:1=图片 2=视频 3=文件 4=音频 */
    private Integer type;
    /** 原始文件名 */
    private String original_name;
    /** 磁盘存储文件名 */
    private String storage_path;
    /** 访问URL */
    private String url;
    /** 文件字节数 */
    private Long size;
    /** MIME类型 */
    private String mime_type;
    /** 文件MD5 */
    private String md5;
    /** 状态 */
    private Integer status;
    /** 创建时间 */
    private String created_at;
    /** 更新时间 */
    private String updated_at;

    /** 从实体构造视图 */
    public static ResourceView from(Resource r) {
        return new ResourceView(r.getId(), r.getUserId(), r.getType(), r.getOriginalName(),
                r.getStoragePath(), r.getUrl(), r.getSize(), r.getMimeType(), r.getMd5(),
                r.getStatus(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
