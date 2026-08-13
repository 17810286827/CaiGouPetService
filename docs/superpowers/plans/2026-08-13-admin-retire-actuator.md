# admin 下线 + 删除 Express server/ + 引入 Actuator 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 下线 admin 管理后台（唯一未迁移的 Express 模块）、彻底删除前端仓库的 `server/` 目录，并同批引入 Spring Boot Actuator 作为监控替代，达成"单一 Java 后端"。

**架构：** 后端 `CaiGouPetService`（Java）加 `spring-boot-starter-actuator` 并暴露 health/info/metrics，替代 admin.html 的监控职能；前端 `CaiGouPet` 删除整个 `server/`（Express），清理 `EXPRESS_PORT` 死代码、启动脚本、文档、.gitignore 中对 Express/server 的全部引用。

**技术栈：** 后端 Spring Boot 4 (webmvc) + MyBatis + MySQL + Actuator；前端 Electron 桌宠（无服务端代码）。

**规格来源：** `docs/superpowers/specs/2026-08-13-admin-retire-actuator-design.md`

---

## 文件结构

### 后端仓库 `CaiGouPetService`
| 文件 | 操作 | 职责 |
|---|---|---|
| `src/test/java/caigou/caigoupetservice/controller/ActuatorHealthIntegrationTest.java` | 创建 | Actuator 端点集成测试 |
| `pom.xml` | 修改 | 加 `spring-boot-starter-actuator` 依赖 |
| `src/main/resources/application.yaml` | 修改 | 加 `management.endpoints.web.exposure.include: health,info,metrics` |

### 前端仓库 `CaiGouPet`
| 文件 | 操作 | 职责 |
|---|---|---|
| `server/`（整个目录，66M） | 删除 | Express 后端：admin.js、admin.html、SQLite、旧 public 页面、logs |
| `.gitignore` | 修改 | 移除 server 专属条目（`server/logs/`、`server/data/auth-token.json`、`uploads/*` 等） |
| `gif-viewer/src/server-api.js` | 修改 | 删除 `EXPRESS_PORT` 常量与相关注释 |
| `start.bat` | 修改 | 去掉"启动 server"步骤，健康等待改 `/actuator/health` |
| `start.ps1` | 修改 | 同上 |
| `start-fix.bat` | 修改 | 同上 |
| `restart.bat` | 不动 | 仅调用 start.bat |
| `ARCHITECTURE.md` | 修改 | 改写 Express 后端描述 → Spring Boot |
| `CLAUDE.md` | 修改 | 改写服务端架构与命令 → 指向 Java 后端 |

---

## 任务 1：后端引入 Actuator（CaiGouPetService 仓库）

**文件：**
- 创建：`src/test/java/caigou/caigoupetservice/controller/ActuatorHealthIntegrationTest.java`
- 修改：`pom.xml`（依赖块）、`src/main/resources/application.yaml`（追加 management 段）

- [ ] **步骤 1：编写失败的测试**

创建 `src/test/java/caigou/caigoupetservice/controller/ActuatorHealthIntegrationTest.java`：

```java
package caigou.caigoupetservice.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actuator 监控端点集成测试:替代已下线的 Express admin 面板
 * 验证 /actuator/health 返回 UP(含 MySQL 连通)、/actuator/info 可访问
 * 运行前提:MySQL 可达(caigoupet 库),密码通过环境变量 DB_PASS 提供
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorHealthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** 健康检查应返回 200 且顶层 status=UP */
    @Test
    void health_shouldReturnUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /** info 端点应可访问(200),当前默认返回空对象 */
    @Test
    void info_shouldReturn200() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`env DB_PASS='chen9911.' ./mvnw test -Dtest=ActuatorHealthIntegrationTest`

预期：FAIL。Actuator 未引入，`/actuator/health` 无映射 → 404，`status().isOk()` 断言失败（报 `Status expected:200 but was:404`）。说明测试先于实现、红在正确位置。

- [ ] **步骤 3：加 Actuator 依赖**

在 `pom.xml` 的 `<dependencies>` 中、`spring-boot-starter-webmvc` 之后追加：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

版本由 `spring-boot-starter-parent`（4.0.7）管理，无需写 version。

- [ ] **步骤 4：配置端点暴露范围**

在 `src/main/resources/application.yaml` 末尾追加：

```yaml
# Actuator 监控:健康检查 + JVM 指标(替代已下线的 Express admin 面板)
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
```

> 仅暴露 health/info/metrics，不暴露 env/beans/threaddump/heapdump 等敏感端点。

- [ ] **步骤 5：运行测试确认通过**

运行：`env DB_PASS='chen9911.' ./mvnw test -Dtest=ActuatorHealthIntegrationTest`

预期：PASS（2 个测试全绿）。`/actuator/health` 返回 `{"status":"UP"}`（MySQL 连通），`/actuator/info` 返回 200。

- [ ] **步骤 6：后端全量测试确认无回归**

运行：`env DB_PASS='chen9911.' ./mvnw test`

预期：全部通过（86 既有 + 2 新增）。若有失败，说明 Actuator 引入影响了既有行为（正常不应有）。

- [ ] **步骤 7：Commit**

```bash
git add pom.xml src/main/resources/application.yaml src/test/java/caigou/caigoupetservice/controller/ActuatorHealthIntegrationTest.java
git commit -m "feat: 引入 Spring Boot Actuator 监控(health/info/metrics) + 集成测试"
```

---

## 任务 2：前端删除 server/ 目录（CaiGouPet 仓库）

**文件：**
- 删除：`server/`（整个目录）
- 修改：`.gitignore`

- [ ] **步骤 1：预检——确认桌面端无 server 引用**

在仓库根目录运行：

```bash
git grep -n "server/\.\|server-api\|/api/health\|uploads/" -- gif-viewer/ | grep -v "src/server-api.js" | head -20
git grep -n "3100" -- gif-viewer/src/ | head
```

预期：无输出（`gif-viewer` 对 `server/`、Express 3100 零引用）。`server-api.js` 中的 `EXPRESS_PORT` 仅定义无调用方，任务 3 清理。

> 若此处出现引用，停下并报告，不要继续删除。

- [ ] **步骤 2：删除整个 server/ 目录**

```bash
cd /d/IDE/project/CaiGouPet
git rm -r server/
```

预期：`server/` 全部文件从索引移除（约 60 个文件）。工作区不再有 `server/` 目录。

- [ ] **步骤 3：清理 .gitignore 中 server 专属条目**

编辑根目录 `.gitignore`，删除以下行（随 server/ 删除而失效）：

```
uploads/*
!uploads/.gitkeep
server/logs/
server/data/auth-token.json
```

保留通用条目：`node_modules/`、`.electron-cache/`、`*.db`、`.env`、`.claude/`、`old/`、`*.log`、`log/`、`ai-config.json`、`gif-viewer/_*.js` 等。

- [ ] **步骤 4：确认无 server/ 残留**

```bash
git grep -n "server/" -- . ':!*.md' | head
ls server/ 2>&1   # 预期: No such file or directory
```

预期：无代码引用（`*.md` 文档引用由任务 3 处理）。

- [ ] **步骤 5：Commit**

```bash
git add -A
git commit -m "chore: 删除 Express server/ 目录(admin 已下线,桌宠零依赖,后端已整体迁至 Java)"
```

---

## 任务 3：前端清理死代码与脚本/文档（CaiGouPet 仓库）

**文件：**
- 修改：`gif-viewer/src/server-api.js`
- 修改：`start.bat`、`start.ps1`、`start-fix.bat`（`restart.bat` 不动）
- 修改：`ARCHITECTURE.md`、`CLAUDE.md`

- [ ] **步骤 1：清理 server-api.js 的 EXPRESS_PORT 死代码**

编辑 `gif-viewer/src/server-api.js`：

- 删除第 8-9 行（EXPRESS_PORT 注释 + 常量定义）：

```js
// Express(未迁移模块 chat/pet/plugins)所在端口:默认 3100,与 pet-socket.js 的 socket 指向保持一致
const EXPRESS_PORT = parseInt(process.env.CAIGOPET_EXPRESS_PORT, 10) || 3100;
```

- 修改 `apiRequest` 内的注释（第 26 行附近）：

```js
// 默认走 Java(3000);port 参数保留为兼容,当前无调用方传值
port: port || API_PORT,
```

- 修改文件末尾导出（删除 `EXPRESS_PORT`）：

```js
module.exports = { apiRequest };
```

- 文件头注释同步更新（去掉"Express 未迁移模块"表述）：

```js
// API 服务地址:默认指向新后端(Java)3000,可用环境变量 CAIGOPET_API_HOST/CAIGOPET_API_PORT 覆盖
const API_HOST = process.env.CAIGOPET_API_HOST || 'localhost';
const API_PORT = parseInt(process.env.CAIGOPET_API_PORT, 10) || 3000;
```

- [ ] **步骤 2：更新 start.bat**

整体替换 `start.bat` 为（去掉启动 Express，健康等待改 `/actuator/health`）：

```bat
@echo off
echo [0/2] Killing old processes...
taskkill /f /im electron.exe 2>nul
taskkill /f /im node.exe 2>nul
timeout /t 1 /nobreak >nul
echo [1/2] Waiting for Java backend (3000)...
:wait_health
timeout /t 2 /nobreak >nul
powershell -NoProfile -Command "try { $null = Invoke-WebRequest 'http://localhost:3000/actuator/health' -UseBasicParsing -TimeoutSec 2; exit 0 } catch { exit 1 }" >nul 2>&1
if errorlevel 1 goto wait_health
echo Java backend is ready!
echo.
echo ========================================
echo Java Backend: http://localhost:3000
echo Monitor:     http://localhost:3000/actuator/health
echo.
echo ========================================
echo.
cd /d "%~dp0gif-viewer"
echo [2/2] Starting CaigoPet...
call npm start
```

- [ ] **步骤 3：更新 start.ps1**

整体替换 `start.ps1` 为：

```powershell
# CaigoPet Start Script
$dir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "Killing old processes..." -ForegroundColor Cyan
Get-Process -Name "electron","node" -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

Write-Host "[1/2] Waiting for Java backend (http://localhost:3000/actuator/health)..." -ForegroundColor Cyan
$ready = $false
while (-not $ready) {
    Start-Sleep -Seconds 2
    try {
        $null = Invoke-WebRequest 'http://localhost:3000/actuator/health' -UseBasicParsing -TimeoutSec 2
        $ready = $true
    } catch { }
}
Write-Host "Java backend is ready!" -ForegroundColor Green

Write-Host "[2/2] Starting CaigoPet..." -ForegroundColor Cyan
$gifDir = Join-Path $dir "gif-viewer"
Start-Process -FilePath "npm" -ArgumentList "start" -WorkingDirectory $gifDir -WindowStyle Hidden

Write-Host "Done. Login window will appear shortly." -ForegroundColor Green
```

- [ ] **步骤 4：更新 start-fix.bat**

整体替换 `start-fix.bat` 为（去掉启动 Express，加健康等待）：

```bat
@echo off
echo ========================================
echo  KILLING ALL CAIGOPET PROCESSES...
echo ========================================
taskkill /f /im electron.exe 2>nul
taskkill /f /im node.exe 2>nul
timeout /t 2 /nobreak >nul
echo.
echo [0/2] Starting fresh...
echo.
echo [1/2] Waiting for Java backend (3000)...
:wait_health
timeout /t 2 /nobreak >nul
powershell -NoProfile -Command "try { $null = Invoke-WebRequest 'http://localhost:3000/actuator/health' -UseBasicParsing -TimeoutSec 2; exit 0 } catch { exit 1 }" >nul 2>&1
if errorlevel 1 goto wait_health
echo Java backend is ready!
echo [2/2] Starting CaigoPet...
cd /d "%~dp0gif-viewer"
call npm start
```

> `restart.bat` 仅调用 start.bat，无需改动。

- [ ] **步骤 5：更新 ARCHITECTURE.md**

编辑 `ARCHITECTURE.md`，按以下修改点改写（Express → Spring Boot，标注独立仓库）：

| 位置 | 改动 |
|---|---|
| 第 8 行 | `2. **server**：Node.js Express 社区后端（用户、帖子、聊天室）` → `2. **CaiGouPetService**（独立仓库 `D:/IDE/project/CaiGouPetService`）：Spring Boot 社区后端（REST 3000 + socket 3001）` |
| 第 39-45 行 mermaid 服务端 subgraph | 改为 `subgraph "服务端 (Spring Boot, 独立仓库)"`，节点：`SERVER[Spring Boot 服务器 - REST 3000]`、`SOCKET[netty-socketio - 3001]`、`MODELS[MyBatis 映射层]`、`MYSQL[(MySQL)]`、`ROUTES[Controller 路由层]`；末尾连线 `MODELS --> MYSQL`（替换 `MODELS --> SQLITE`） |
| 第 172-178 行 技术栈表 | `HTTP 框架: Spring Boot 4 (webmvc)`、`ORM: MyBatis`、`数据库: MySQL`、`实时通信: netty-socketio`、`认证: JWT (jjwt)`、`文件上传: Spring multipart`、`密码加密: spring-security-crypto (bcrypt)` |
| 第 180-193 行 数据模型 | 改写为：`数据表(MySQL, 见 CaiGouPetService 的 entity 层): User/Post/Comment/Like/Favorite/Follow/Resource/Message/ChatRoom/ChatRoomMember/PetState/PetVisitSetting/Plugin/PluginFavorite`，注明"关联关系见后端实体注解" |
| 第 195-209 行 路由结构 | 改写为 Java Controller 清单：`AuthController/ChatController/CommentController/FavoriteController/FollowController/LikeController/PetController/PluginController/PostController/ResourceController/UserController`，前缀 `/api/*` 不变 |
| 第 210-213 行 WebSocket | 改为 `socket/SocketConfig.java 基于 netty-socketio(3001) 实现: chat:join/message/typing/read + pet:interact,握手走 query token 鉴权` |
| 第 238-243 行 数据同步 | 删去 sync-export/import 行，替换为 `数据迁移已完成: 旧 Express/SQLite 数据不再维护` |
| 第 251 行 agent 对比表 | `后端 Node.js Express + SQLite` → `Spring Boot + MySQL` |

- [ ] **步骤 6：更新 CLAUDE.md**

编辑前端 `CLAUDE.md`，按以下修改点改写：

| 位置 | 改动 |
|---|---|
| 第 10 行 | `2. **server/** — Node.js Express 社区后端（用户、帖子、聊天室、插件）` → `2. **CaiGouPetService**（独立仓库）— Spring Boot 后端（REST 3000 + socket 3001），本仓库已不含服务端代码` |
| 第 15 行 | 删去"server 模型已扩展到 14 个"，改为"后端已整体迁移至独立的 CaiGouPetService(Java) 仓库" |
| 第 20-32 行 常用命令 | 后端启动改为：`cd D:/IDE/project/CaiGouPetService && env socket.enabled=true DB_PASS='chen9911.' ./mvnw spring-boot:run  # REST 3000 + socket 3001`；删除 `cd server && npm start/dev/test` 三行；保留 gif-viewer 的 npm 命令 |
| 第 34 行 | 改为"`start.bat` / `start.ps1` 仅负责等待 Java 后端(3000, /actuator/health)就绪后拉起 gif-viewer;Java 后端需另行启动(见上)" |
| 第 69-76 行 `## 服务端架构（server）` 整节 | 替换为 `## 服务端架构（CaiGouPetService, 独立仓库）`，内容：`技术栈: Spring Boot 4 (webmvc) + MyBatis + MySQL + netty-socketio + JWT`、`端口: REST 3000 + socket 3001(socket.enabled=true)`、`端点前缀: /api/auth、users、resources、posts、comments、pet、chat、follow、likes、favorites、plugins`、`监控: Actuator(/actuator/health、/actuator/info、/actuator/metrics)`、`认证: JWT 拦截器(@PublicEndpoint 放行公开端点)`、`测试: src/test/.../*ApiIntegrationTest(连真实 MySQL, DB_PASS 提供密码)` |
| 第 81-82 行 配置与密钥 | 删 `**服务端配置**：server/.env（从 server/.env.example 复制）...`；改为 `**服务端配置**：CaiGouPetService 的 src/main/resources/application.yaml（数据源 192.168.31.90/caigoupet，密码走 DB_PASS 环境变量）`；"禁止提交"清单删去 `server/logs/` |

- [ ] **步骤 7：残留检查**

在仓库根目录运行：

```bash
git grep -n "EXPRESS_PORT\|Express\|3100\|server/" -- . ':!*.db' | grep -v "gif-viewer/src/server-api.js:8" | head -20
```

预期：仅文档中剩余的泛化提及（如有）人工确认无实质引用；代码无 `EXPRESS_PORT`/`3100`/`server/` 残留。

- [ ] **步骤 8：Commit**

```bash
git add gif-viewer/src/server-api.js start.bat start.ps1 start-fix.bat ARCHITECTURE.md CLAUDE.md
git commit -m "refactor: 清理 EXPRESS_PORT 死代码 + 启动脚本与文档指向 Java 后端"
```

---

## 任务 4：端到端回归验证

**文件：** 无（纯验证）

- [ ] **步骤 1：启动 Java 后端**

运行：`cd D:/IDE/project/CaiGouPetService && env socket.enabled=true DB_PASS='chen9911.' ./mvnw spring-boot:run`

预期：REST 监听 3000，socket 监听 3001（日志 `socket.enabled=true` 生效）。

- [ ] **步骤 2：验证 Actuator 与核心接口**

```bash
# Actuator(新增)
curl -s http://localhost:3000/actuator/health   # 预期 {"status":"UP"}
curl -s http://localhost:3000/actuator/info     # 预期 200

# 核心接口走 Java(注册用户后带 token 验证 chat/pet/plugins 各一条)
curl -s -X POST http://localhost:3000/api/auth/register -H "Content-Type: application/json" -d '{"username":"e2e_admin_retire","password":"pass123"}'
```

预期：health `UP`、register 201。随后用返回 token 分别调用 `GET /api/chat/rooms`、`GET /api/pet/state`、`GET /api/plugins/categories`，均返回 200 且非 404。

- [ ] **步骤 3：桌宠冒烟启动**

`cd D:/IDE/project/CaiGouPet && ./start.bat`（或 gif-viewer `npm start`），确认登录窗口出现、chat/pet 面板功能正常（人工确认）。

- [ ] **步骤 4：测试数据清理**

若步骤 2 注册了 `e2e_admin_retire` 测试用户，执行：

```bash
env DB_PASS='chen9911.' mysql -h 192.168.31.90 -u root -p'chen9911.' caigoupet -e "DELETE FROM users WHERE username='e2e_admin_retire';"
```

预期：测试用户清理，不留脏数据。

---

## 自检记录

- **规格覆盖度**：4.1 后端 Actuator（任务 1）✅；4.2 删除 server/（任务 2）、死代码/脚本/文档/.gitignore（任务 3）✅；4.3 验证（任务 4）✅。非目标项均未纳入任务。
- **占位符扫描**：所有代码步骤含实际内容，无"待定/TODO/类似任务 N"。文档修改点给出具体行号与替换文本。
- **类型一致性**：`ActuatorHealthIntegrationTest` 在任务 1 定义并被验证引用；`/actuator/health` 路径在任务 1（测试）、任务 2（启动脚本预检不涉及）、任务 3（start.bat 等）、任务 4（curl）中一致。
