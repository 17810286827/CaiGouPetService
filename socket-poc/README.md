# socket-poc — netty-socketio 与 socket.io-client v4 连通性验证

## 结论(verdict)

- **验证日期**:2026-08-11
- **结果**:✅ **PASS** —— netty-socketio(2.0.13)与 socket.io-client v4.8.3 可正常连通
- **对批次 2 的结论**:netty-socketio 可用,**继续原方案**(无需降级方案 B 原生 WebSocket)

## 验证过程

> 说明:socket 服务已做属性门控,默认**不启动**;启动后端时必须显式开启
> `socket.enabled=true`(环境变量或 JVM 参数均可),否则 3001 端口无监听。

后端(Spring Boot 4.0.7 / Java 21):`socket.enabled=true DB_PASS='chen9911.' ./mvnw spring-boot:run`
- 日志确认 socket 服务启动:`SocketIO server started at port: 3001`
- 测试脚本:`cd socket-poc && npm install && node test-socket.js`(客户端 v4.8.3,强制 websocket 传输)

### 实际输出

```
[A] connected id=c83912f4-7efa-4f7e-86bb-efd8b16facc1
[B] connected id=663fc008-d7da-4193-94ea-d3358c2c374e
[B] received chat:message = {"content":"hello-poc","room_id":"room1","user_id":"unknown"}
POC PASS: 房间广播双向收发成功
```

后端侧日志:两个客户端握手均通过(授权放行)、均成功 join `room1`、chat:message 广播送达。

## 验证覆盖与结论范围

| 项目 | 结果 | 说明 |
| --- | --- | --- |
| websocket 传输握手 | ✅ | 强制 `transports: ['websocket']`,验证了净 websocket 兼容 |
| 双客户端连接 | ✅ | A、B 均成功连接并拿到 sessionId |
| chat:join 加入房间 | ✅ | 双端加入 room1 成功 |
| chat:message 房间广播 | ✅ | A 发、B 收,`room_id` 与 `content` 完全匹配 |
| 鉴权回调 | ⚠️ | 放行正常,但服务端 `getAuthToken()` 返回 **null**(见下方"发现的问题") |

## 发现的问题(对批次 2 的注意点)

1. **`authToken` 解析为 null**:客户端发送 `auth: { token: 'poc-token' }`,但服务端
   `HandshakeData.getAuthToken()` 拿到 null。说明 netty-socketio 2.0.13 可能未完整解析
   socket.io v4 的 auth 握手载荷。**批次 2 的 SocketAuthListener 若打算从握手 auth 中取
   token 校验,需在 Task 2 先行验证该取数路径**(必要时改为从 URL query 传 token)。
2. **API 与官方文档/计划代码有差异**(已按 2.0.13 实际 API 适配):
   - `AuthorizationListener` 返回 `AuthorizationResult`(非 boolean)→ 用 `SUCCESSFUL_AUTHORIZATION`
   - `HandshakeData` 无 `get(String)`,改为从 `getAuthToken()` 的 Map 中取 userId
   - `javax.annotation.PreDestroy` 在 Boot 4(Jakarta)classpath 不存在,已删除该未使用 import
3. **连接体验**:客户端 A 的 `connect` 事件先于 B 注册监听,故测试脚本依赖 A 的 connect 事件
   先行 resolve;若连接失败会立即打印 `connect_error` 并以退出码 1 结束。

## 复现方式

```bash
# 1. 启动后端(需连真实 MySQL 提供 DB_PASS,并显式开启 socket 服务)
socket.enabled=true DB_PASS='chen9911.' ./mvnw spring-boot:run
# 2. 另开终端运行 POC 脚本
cd socket-poc && npm install && node test-socket.js
```

预期输出末尾出现 `POC PASS: 房间广播双向收发成功` 且退出码为 0。
未设置 `socket.enabled=true` 时 socket 不启动(测试环境即如此,避免多测试上下文端口冲突)。
