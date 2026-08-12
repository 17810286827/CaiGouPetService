package caigou.caigoupetservice.util;

/**
 * 分页参数钳制工具:对齐 Express 分页行为,page 最小 1、limit 上限 100
 */
public final class Pagination {

    /** 页码钳制:小于 1 一律按 1 处理,避免负 offset 触发 SQL 500 */
    public static int clampPage(int page) {
        return Math.max(page, 1);
    }

    /** 每页条数钳制:下限 1、上限 100,防止超大 limit 拉全表 */
    public static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), 100);
    }
}
