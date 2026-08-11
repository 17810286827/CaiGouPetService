package caigou.caigoupetservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 通用分页视图:序列化为 {rows, total, page}
 * rows 为当前页数据,序列化时字段名由子类/返回对象决定(如 posts/comments/followers)
 */
@Data
@AllArgsConstructor
public class PageView<T> {

    /** 当前页数据 */
    private List<T> rows;

    /** 总记录数 */
    private long total;

    /** 当前页码(从 1 开始) */
    private long page;
}
