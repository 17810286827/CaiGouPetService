# 设计文档：admin 下线 + 彻底删除 Express server/ + 引入 Spring Boot Actuator

> 日期：2026-08-13
> 仓库：`CaiGouPetService`（后端，Java）+ `CaiGouPet`（前端，Electron 桌宠）
> 关联交接：`HandOff/handoff-2026-08-13-batch2-chat-socket-pet-plugins.md`

## 一、背景与目标

上一批迁移（P3-P5）已将 chat/socket/pet/plugins 四个模块迁到 Spring Boot。交接记录中剩余工作的**唯一功能模块**是 `admin` 管理后台（Express），并保留着旧的 Express `server/` 目录。

本次目标：

1. **下线 admin 模块**——唯一未迁移的 Express 接口模块，桌宠零使用。
2. **彻底删除前端 `CaiGouPet` 仓库的 `server/` 目录**——Express 已无任何消费者，删除后达成"单一 Java 后端"。
3. **同批引入 Spring Boot Actuator**——作为 admin 面板监控职能的替代，提供健康检查 + JVM 指标端点。
4. **清理所有对 Express/server 的引用**——启动脚本、文档、前端死代码。

## 二、现状关键发现

探索确认的事实（决定方案走向）：

1. **admin 是唯一未迁移模块**：`CaiGouPet/server/src/routes/` 仅剩 `admin.js`。
2. **消费方单一**：admin 15 个接口唯一消费方是 `server/public/admin.html`（开发者运维页，每 5s 轮询），桌宠 gif-viewer 完全不调用。admin.html 实际只用 5 个接口：`/health` `/dashboard` `/db/tables` `/security/status` `/bandwidth`。
3. **admin 是 SQLite 专用**：analytics/monitor 全用 SQLite 语法（`DATE('now',...)`、`strftime`、`VACUUM INTO` 备份、`sqlite_master` 索引分析），而 Spring Boot 是 MySQL（192.168.31.90/caigoupet）——即使迁移，所有 SQL 都要改写。
4. **数据源分叉**：Express 默认跑 SQLite（无 .env 覆盖），admin 面板读的是 Express 自己的空 SQLite，**不是桌宠真实数据**。
5. **内存态重依赖**：bandwidth/security 是 Express 进程内存态（带宽统计、封禁 IP、请求日志），Spring Boot 无对应物；且桌宠流量已走 Java，迁移后统计从零空转。
6. **系统资源语义**：`/health` 返回 Node 进程指标（`heapUsed`/`heapTotal`/`uptime`），admin.html 直接依赖这些字段。
7. **鉴权名不副实**：`adminAuth` 实际只做普通 JWT 校验（代码有 TODO），任何登录用户都能访问 admin。
8. **前端已零依赖 Express**：`server-api.js` 的 `EXPRESS_PORT`(3100) 常量保留但**零调用方**；无 socket.io 引用；无 `/uploads`、`/api/files`、`/api/health` 静态资源引用。前端 Handoff.md 明确"前端对旧 server 目录零引用"。
9. **Java 无 `/api/health` 端点**：前端启动脚本健康等待的是 `http://localhost:3000/api/health`（Java 3000 上不存在），正好由 Actuator 补齐。

## 三、决策记录

| 决策 | 结论 | 理由 |
|---|---|---|
| admin 处理方向 | **下线**，不迁移 | 桌宠零使用、SQLite 专用、依赖 Express 内存态、现有面板读的不是真实数据 |
| server/ 目录 | **彻底删除** | 前端已零依赖，达成单一 Java 后端 |
| admin 替代 | **引入 Spring Boot Actuator** | 官方标准，health/metrics 覆盖 admin 主要职能，比 Express 内存态更真实 |
| Actuator 暴露范围 | **health + info + metrics** | 桌宠/脚本健康检查用 `/actuator/health`，metrics 预留接 Prometheus/Grafana；不暴露 env/beans/threaddump/heapdump 等敏感端点 |

## 四、详细设计

### 4.1 后端：引入 Actuator（`CaiGouPetService`）

**pom.xml**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

**application.yaml** 追加：

```yaml
# Actuator 监控:健康检查 + JVM 指标(替代已下线的 Express admin 面板)
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

> 说明：项目基于 Boot 4 的 `spring-boot-starter-webmvc`，Actuator starter 兼容。`/actuator/health` 为存活 + DB 连通检查（Web 端点默认）。

**新增测试** `src/test/java/caigou/caigoupetservice/controller/ActuatorHealthIntegrationTest.java`

- 照现有 `AuthApiIntegrationTest` 模式：`@SpringBootTest` + `@AutoConfigureMockMvc`
- 断言 `GET /actuator/health` 返回 200 且 `$.status == "UP"`
- 断言 `GET /actuator/info` 返回 200
- 运行前提与其余集成测试一致：MySQL 可达，`DB_PASS` 环境变量提供

### 4.2 前端：删除 server/ 与清理引用（`CaiGouPet`）

**删除整个 `server/` 目录**（66M，含）：

- `src/routes/admin.js`、`src/` 全部 Express 源码（config/db/middleware/models/services/socket/utils）
- `public/`（admin.html 及 login/chat/posts/profile/pet 等旧浏览器页面）
- `data/caigopet.db`（旧 SQLite 数据）
- `logs/`、`electron/`（旧登录进程，已迁 gif-viewer/login-electron）
- `scripts/seed-plugins.js`、`package.json`、`.env.example`、`.gitignore`

**删除前端死代码**：`gif-viewer/src/server-api.js`

- 删除 `EXPRESS_PORT` 常量（及 `CAIGOPET_EXPRESS_PORT` 相关注释）
- `apiRequest` 的 `port` 可选参数**保留**（结构不改，仅更新注释，避免无关改动）
- 更新文件头注释：去掉"Express 未迁移模块"表述

**更新启动脚本**（4 个）：

| 脚本 | 改动 |
|---|---|
| `start.bat` | 删除 `cd server` + `node src/index.js` 步骤；健康等待 URL 改 `http://localhost:3000/actuator/health`；提示文案改为指向 Java 后端 |
| `start.ps1` | 同 `start.bat`：删除 server 启动 Job，等待 `/actuator/health` |
| `start-fix.bat` | 同上，去掉 server 启动 |
| `restart.bat` | 无改动（仅调用 start.bat） |

> 注意：Java 后端由开发者另行启动（`./mvnw spring-boot:run`），桌宠脚本只负责等待其就绪并拉起 gif-viewer。

**更新文档**：

- `ARCHITECTURE.md`：改写"服务端 (Express)"部分为"后端已整体迁移至 CaiGouPetService(Java, 端口 3000 + socket 3001)"；更新 HTTP 框架、技术栈等过时表格行
- `CLAUDE.md`：改写 `server/` 描述与启动/测试命令，指向 Java 后端；删除 Express 路由前缀清单
- `Handoff.md`：历史交接记录，保留不动（git 历史中可查）

**清理 .gitignore**：移除 `server/logs/`、`server/data/auth-token.json`、`uploads/*` 等 server 专属条目（保留通用条目：node_modules/、.env、*.db 等）。

### 4.3 验证方案

| 层 | 验证 | 命令/方式 |
|---|---|---|
| 后端全量 | 全量测试通过 | `DB_PASS='chen9911.' ./mvnw test`（86 既有 + 新增 Actuator 测试） |
| 前端残留 | 无 server/Express 引用 | `git grep -n "EXPRESS_PORT\|3100\|server/" -- 排除已删`（注意排除 docs 历史） |
| 端到端 | 桌宠核心链路走 Java 无回归 | 启动 Java(3000+3001, `socket.enabled=true`) + 桌宠，验证登录/chat/pet/plugins |

## 五、非目标（YAGNI）

- 不迁移任何 admin 接口到 Java（方向为下线，非改写）
- 不实现带宽/安全统计的 Java 版拦截器（桌宠流量走后这些从零空转，无价值）
- 不接 Prometheus/Grafana（metrics 端点预留即可）
- 不做 admin.html 的 Java 替代页面（Actuator 端点即替代）
- 不清理 Java 后端 uploads 下已迁移模块的存量旧文件（不在本次范围）

## 六、风险与注意事项

1. **`server/` 删除是大操作**：66M 含历史数据，git 历史可回溯；删除前确认桌宠无引用（已核实）。
2. **`server/public/` 旧浏览器页面随之消失**：前端 Handoff.md 已确认零引用，桌宠不受影响。
3. **启动脚本健康 URL 变化**：`/api/health` → `/actuator/health`，若 Java 未起则桌宠脚本等待（符合预期）。
4. **测试连真实 MySQL**：`DB_PASS='chen9911.'`，新增 Actuator 测试不写库（只读 health），无数据污染。
5. **两个仓库独立 git**：后端操作在 `CaiGouPetService`，前端操作在 `CaiGouPet`，各自 commit。
