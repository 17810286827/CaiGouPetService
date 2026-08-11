package caigou.caigoupetservice.entity;

import lombok.Data;

/**
 * 资源实体,对应 resources 表(上传文件元数据)
 * 数据库下划线字段经 mybatis map-underscore-to-camel-case 自动映射为驼峰属性
 */
@Data
public class Resource {

    /** 主键(自增) */
    private Long id;
    /** 上传者ID */
    private Long userId;
    /** 类型:1=图片 2=视频 3=文件 4=音频 */
    private Integer type;
    /** 原始文件名 */
    private String originalName;
    /** 磁盘存储文件名(uuid) */
    private String storagePath;
    /** 访问URL */
    private String url;
    /** 缩略图URL */
    private String thumbnailUrl;
    /** 文件字节数 */
    private Long size;
    /** MIME类型 */
    private String mimeType;
    /** 图片宽度 */
    private Integer width;
    /** 图片高度 */
    private Integer height;
    /** 音视频时长(秒) */
    private Integer duration;
    /** 文件MD5(去重) */
    private String md5;
    /** 状态:1=正常 0=删除 */
    private Integer status;
    /** 创建时间(DB 维护) */
    private String createdAt;
    /** 更新时间(DB 维护) */
    private String updatedAt;
}
