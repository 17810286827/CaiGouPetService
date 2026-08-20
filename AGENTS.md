# 仓库指南

## 项目结构与模块组织

本仓库是使用 Maven 构建的 Java 21 Spring Boot 服务。生产代码位于 `src/main/java/caigou/caigoupetservice`，并按职责划分：`controller/` 负责 REST 参数校验和响应映射，`service/` 承载业务逻辑与事务，`mapper/`、`entity/` 和 `dto/` 定义持久化及 API 契约。横切关注点代码放在 `config/`、`interceptor/`、`exception/`、`socket/` 或 `util/`。配置与数据库 DDL 位于 `src/main/resources/`。测试代码在 `src/test/java/` 下镜像生产代码的包结构。API 与数据库结构文档位于 `docs/` 和 `doc/`；`socket-poc/` 是独立的 Socket.IO 概念验证项目。`uploads/` 和 `target/` 均视为运行时或生成内容。

## 构建、测试与开发命令

使用 Maven Wrapper 以保证构建环境一致：

```powershell
.\mvnw.cmd clean package       # 编译、测试并构建 JAR
.\mvnw.cmd test                # 运行完整测试套件
.\mvnw.cmd spring-boot:run     # 在本地启动服务
```

本地启动前，需在 `src/main/resources/application.yaml` 或环境专用覆盖配置中提供有效的 MySQL 与 JWT 设置。

## 编码风格与命名约定

使用四个空格缩进，每个文件只定义一个顶层类。遵循 Java 命名规范：类使用 `UpperCamelCase`，方法和字段使用 `lowerCamelCase`，常量使用 `UPPER_SNAKE_CASE`。保持 Controller 精简，只负责参数校验、调用 Service、映射响应并统一捕获异常。业务规则应放在 Service 层，并由该层抛出项目异常。重要方法和不直观的逻辑必须添加中文注释，代码注释率不得低于 20%。在关键状态变更处记录日志，但不得暴露凭据、令牌或请求载荷内容。向 `schema.sql` 添加数据库字段时，每个字段都必须包含注释。

## 测试指南

测试使用 Spring Boot 和 MyBatis 测试支持。单元测试命名为 `*Test`，端到端 Spring 测试命名为 `*IntegrationTest`。测试文件应放在与被测生产类相同的包路径下，并覆盖接口校验、正常流程、异常映射及持久化行为。提交变更前运行 `.\mvnw.cmd test`。

## 提交与 Pull Request 指南

提交主题应简洁明确。历史提交通常遵循 Conventional Commits，使用 `feat:`、`fix:`、`style:` 和 `docs:` 等前缀，允许使用中文描述。每次提交应聚焦于单一目标。Pull Request 需要概述行为及数据库结构影响，列出验证命令和结果，关联相关 Issue 或设计文档，并为可见的 API/UI 变更提供截图。涉及配置或安全的变更必须明确说明。

## 安全与配置建议

禁止提交 JWT 密钥、MySQL 凭据或特定机器的上传路径。仔细审查对 `PublicEndpoint` 和 `JwtAuthInterceptor` 的修改，并确保生成文件不进入版本控制。
