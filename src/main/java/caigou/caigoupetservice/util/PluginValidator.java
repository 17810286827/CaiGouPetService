package caigou.caigoupetservice.util;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 插件清单校验器:逐行复刻 Express utils/plugin-validator.js 的校验规则
 * validate(manifest, files) 返回 {valid, errors[], warnings[]}:
 * 必填字段存在 / id 格式 / entry 必须 .html / entry 文件在 zip 内 / 总大小 / 文件扩展名白名单 / 文本内容危险模式扫描
 * 注意:与 oracle 一致,validate 不校验 category(仅导出 VALID_CATEGORIES 白名单供列表过滤用)
 */
public final class PluginValidator {

    /** 分类白名单(与 Express VALID_CATEGORIES 一致;仅用于列表过滤,不在上传校验中拒绝非法分类) */
    public static final List<String> VALID_CATEGORIES = List.of("tool", "game", "utility", "social", "customization", "other");

    /** manifest.json 必填字段(与 Express REQUIRED_MANIFEST_FIELDS 一致) */
    public static final List<String> REQUIRED_MANIFEST_FIELDS = List.of("id", "name", "version", "description", "author", "entry");

    /** 插件包内允许的文件扩展名(与 Express ALLOWED_EXTENSIONS 一致) */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "html", "htm", "css", "js", "json",
            "png", "jpg", "jpeg", "gif", "svg", "ico", "webp",
            "woff", "woff2", "ttf", "eot",
            "md", "txt");

    /** 权限白名单(未知权限仅记 warning,与 Express 一致) */
    private static final List<String> VALID_PERMISSIONS = List.of("storage", "notify", "http");

    /** 单个文本文件大小上限:5MB(与 Express MAX_FILE_SIZE 一致) */
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    /** 插件包总大小上限:10MB(与 Express MAX_PLUGIN_SIZE 一致) */
    private static final long MAX_PLUGIN_SIZE = 10L * 1024 * 1024;

    /** 需要内容安全扫描的文本类扩展名 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of("html", "htm", "css", "js", "json", "md", "txt");

    /** 危险模式(与 Express DANGEROUS_PATTERNS 逐条一致,Java 侧加 (?i) 等价 JS 的 /i 标志) */
    private static final Pattern[] DANGEROUS_PATTERNS = {
            Pattern.compile("(?i)child_process"),
            Pattern.compile("(?i)require\\s*\\(\\s*['\"]fs['\"]\\s*\\)"),
            Pattern.compile("(?i)require\\s*\\(\\s*['\"]net['\"]\\s*\\)"),
            Pattern.compile("(?i)process\\.(exit|kill|abort)"),
            Pattern.compile("(?i)__dirname\\s*="),
            Pattern.compile("(?i)eval\\s*\\("),
            Pattern.compile("(?i)new\\s+Function\\s*\\("),
    };

    /** 与 DANGEROUS_PATTERNS 一一对应的原因描述 */
    private static final String[] DANGEROUS_REASONS = {
            "child_process access",
            "fs module access",
            "net module access",
            "process control",
            "dirname reassignment",
            "eval() call",
            "new Function() call",
    };

    private PluginValidator() {
        // 工具类禁止实例化
    }

    /**
     * 完整校验插件:manifest 必填/格式 → entry 文件存在 → 总大小 → 逐文件扩展名与内容安全
     * @param manifest 已解析的 manifest.json(Jackson JsonNode)
     * @param files    插件包内全部非目录文件(含 manifest.json 自身)
     * @return 校验结果 {valid, errors, warnings};errors 非空即不合法
     */
    public static Result validate(JsonNode manifest, List<PluginFile> files) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 非对象直接判非法(与 Express validateManifest 首行一致)
        if (manifest == null || !manifest.isObject()) {
            errors.add("manifest.json is not a valid JSON object");
            return new Result(false, errors, warnings);
        }

        // 必填字段:缺失/null/空串记 Missing required field(镜像 JS !manifest[field] 假值语义;
        // 注意仅空串视为缺失,纯空白字符串在 JS 中为 truthy,不算缺失)
        for (String field : REQUIRED_MANIFEST_FIELDS) {
            JsonNode v = manifest.get(field);
            if (v == null || v.isNull() || (v.isValueNode() && v.asText("").isEmpty())) {
                errors.add("Missing required field: \"" + field + "\"");
            }
        }

        // id 格式:仅允许字母/数字/点/中划线/下划线,首字符不能是点或符号(镜像 JS /^[a-z0-9][a-z0-9._-]*$/i)
        String id = text(manifest, "id");
        if (id != null && !id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")) {
            errors.add("Plugin \"id\" must contain only letters, numbers, dots, hyphens, and underscores");
        }

        // 版本:不满足 semver 前缀(数字.数字.数字)仅记 warning,不拒绝
        String version = text(manifest, "version");
        if (version != null && !version.matches("\\d+\\.\\d+\\.\\d+.*")) {
            warnings.add("Version should follow semver format (x.y.z)");
        }

        // entry:必须为 .html 结尾(缺失时走必填字段错误,这里跳过)
        String entry = text(manifest, "entry");
        if (entry != null && !entry.endsWith(".html")) {
            errors.add("Plugin \"entry\" must be an .html file");
        }

        // 未知权限记 warning
        JsonNode permissions = manifest.get("permissions");
        if (permissions != null && permissions.isArray()) {
            for (JsonNode perm : permissions) {
                String p = perm.isValueNode() ? perm.asText() : "";
                if (!VALID_PERMISSIONS.contains(p)) {
                    warnings.add("Unknown permission: \"" + p + "\". Allowed: storage, notify, http");
                }
            }
        }

        // 宽高越界记 warning
        JsonNode widthNode = manifest.get("width");
        if (widthNode != null && widthNode.isNumber()) {
            int width = widthNode.asInt();
            if (width < 200 || width > 1200) {
                warnings.add("Width should be between 200 and 1200");
            }
        }
        JsonNode heightNode = manifest.get("height");
        if (heightNode != null && heightNode.isNumber()) {
            int height = heightNode.asInt();
            if (height < 150 || height > 900) {
                warnings.add("Height should be between 150 and 900");
            }
        }

        // manifest 本身非法则直接返回,不再做文件级校验(镜像 JS validatePlugin 提前 return)
        if (!errors.isEmpty()) {
            return new Result(false, errors, warnings);
        }

        // entry 引用的文件必须存在于 zip 内(支持根路径或任意子目录下同名)
        if (entry != null) {
            String finalEntry = entry;
            boolean found = files.stream().anyMatch(f -> f.getName().equals(finalEntry) || f.getName().endsWith("/" + finalEntry));
            if (!found) {
                errors.add("Entry file \"" + finalEntry + "\" not found in plugin package");
                return new Result(false, errors, warnings);
            }
        }

        // 总大小上限:10MB(超限记 error,与 Express 文案格式对齐)
        long totalSize = 0;
        for (PluginFile f : files) {
            totalSize += f.getContent().length;
        }
        if (totalSize > MAX_PLUGIN_SIZE) {
            errors.add("Total plugin size exceeds max (10MB): " + String.format(Locale.ROOT, "%.1f", totalSize / 1024.0 / 1024.0) + "MB");
        }

        // 逐文件:扩展名白名单 + 文本内容危险模式扫描(扫描结果加 Security: 前缀,镜像 JS)
        for (PluginFile f : files) {
            if (!isAllowedExtension(f.getName())) {
                errors.add("Disallowed file type: " + f.getName());
                continue;
            }
            for (String issue : scanFileContent(f)) {
                errors.add("Security: " + issue);
            }
        }

        return new Result(errors.isEmpty(), errors, warnings);
    }

    /** 取 manifest 字段文本;缺失/null/空串/非标量返回 null(镜像 JS 假值语义) */
    private static String text(JsonNode manifest, String field) {
        JsonNode v = manifest.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asText();
        return s.isEmpty() ? null : s;
    }

    /** 扩展名是否在白名单内(镜像 JS isAllowedExtension:无扩展名视为不允许) */
    private static boolean isAllowedExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        String ext = dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return !ext.isEmpty() && ALLOWED_EXTENSIONS.contains(ext);
    }

    /**
     * 文本文件内容安全扫描:仅扫文本类扩展名;单文件超 5MB 报错;命中危险模式记 issue
     * @return 问题列表(空=安全)
     */
    private static List<String> scanFileContent(PluginFile file) {
        List<String> issues = new ArrayList<>();
        int dot = file.getName().lastIndexOf('.');
        String ext = dot >= 0 ? file.getName().substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!TEXT_EXTENSIONS.contains(ext)) {
            return issues;
        }
        if (file.getContent().length > MAX_FILE_SIZE) {
            issues.add("File " + file.getName() + " exceeds max size (5MB)");
            return issues;
        }
        String contentStr = new String(file.getContent(), StandardCharsets.UTF_8);
        for (int i = 0; i < DANGEROUS_PATTERNS.length; i++) {
            if (DANGEROUS_PATTERNS[i].matcher(contentStr).find()) {
                issues.add(file.getName() + ": " + DANGEROUS_REASONS[i]);
            }
        }
        return issues;
    }

    /** 校验结果(不可变):valid=false 时 errors 含全部失败原因,warnings 仅为提示不阻断上传 */
    @Getter
    @AllArgsConstructor
    public static class Result {
        /** 是否通过校验(errors 为空) */
        private final boolean valid;
        /** 校验失败原因列表(HTTP 400 时随 details 返回) */
        private final List<String> errors;
        /** 警告列表(不阻断上传,随响应 warnings 返回) */
        private final List<String> warnings;
    }

    /** 插件包内单个文件(名称 + 原始字节,供内容扫描与大小统计) */
    @Getter
    @AllArgsConstructor
    public static class PluginFile {
        /** 文件路径(entry 名,可为子目录相对路径) */
        private final String name;
        /** 文件内容字节 */
        private final byte[] content;
    }
}
