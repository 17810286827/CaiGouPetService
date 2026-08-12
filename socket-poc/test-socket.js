// socket chat 事件全集验证脚本
// 覆盖:带真实 JWT 握手鉴权(A/B 成功、C 无 token 被拒) + chat:join/leave/typing/stop_typing/read/message/user_joined 双向收发
// 运行:cd socket-poc && npm install && node test-socket.js(需注入 CAIGOPET_TOKEN=真实注册用户 JWT)
const { io } = require('socket.io-client');

const SOCKET_URL = process.env.CAIGOPET_SOCKET_URL || 'http://localhost:3001';
// 调用方注入:真实注册用户 JWT(可 node -e 调 POST /api/auth/register 现取)
const AUTH_TOKEN = process.env.CAIGOPET_TOKEN;
const ROOM = 'room1';
const MSG_ID = 'msg-1001';       // chat:read 断言用 message_id
const MSG_CONTENT = 'hello-poc'; // chat:message 断言用消息内容

// 期望 B 收到的接收事件清单:全部收到且 payload 正确才算 PASS
const RECEIVE_EVENTS = ['chat:user_joined', 'chat:typing', 'chat:stop_typing', 'chat:read', 'chat:message'];
const received = new Map(); // 事件名 -> { ok, payload }

// C(无 token)是否收到了预期的 connect_error
let cRejected = false;

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

function parseJwt(token) {
  // JWT payload 是 base64url 编码的 JSON,取中段解码拿到 userId 做精确断言
  return JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

function markReceived(name, ok, payload) {
  received.set(name, { ok, payload });
}

function connectClient(label, token) {
  const socket = io(SOCKET_URL, {
    transports: ['websocket'], // 强制 websocket,跳过 http 长轮询,验证净 websocket 兼容
    // auth 载荷携带 token(socket.io v4 规范);netty-socketio 2.0.13 的 AuthorizationListener
    // 在 engine.io 握手阶段 getAuthToken() 返回 null(POC 已验证),故同时用 query 传一份,
    // 服务端 resolveToken 优先取 auth、兜底取 getSingleUrlParam("token")
    auth: token ? { token } : {},
    query: token ? { token } : {},
  });
  socket.on('connect', () => console.log(`[${label}] connected id=${socket.id}`));
  socket.on('connect_error', (err) => {
    console.log(`[${label}] connect_error:`, err.message);
    if (label === 'C') {
      cRejected = true; // C 预期被拒,记录下来不退出
    } else {
      process.exit(1); // A/B 预期成功,收到错误即视为失败
    }
  });
  socket.on('disconnect', (r) => console.log(`[${label}] disconnected:`, r));
  return socket;
}

async function main() {
  if (!AUTH_TOKEN) {
    console.error('AUTH FAIL: 未设置 CAIGOPET_TOKEN 环境变量(用注册用户的 JWT)');
    process.exit(1);
  }
  // 服务端事件回显的 user_id 应等于 token 中的真实 userId(任务核心修复点:不再恒为 "unknown")
  const expectUserId = parseJwt(AUTH_TOKEN).userId;

  const a = connectClient('A', AUTH_TOKEN);
  const b = connectClient('B', AUTH_TOKEN);
  await new Promise((resolve) => { a.on('connect', resolve); });
  console.log('AUTH PASS(带 token): A 连接成功');

  // C:无 token 连接,预期握手被拒(connect_error)
  connectClient('C', null).on('connect', () => {
    console.error('AUTH FAIL: 无 token 的 C 竟连接成功');
    process.exit(1);
  });
  await sleep(500); // 给 C 的 connect_error 留出到达时间

  // B 监听 chat 事件全集:收到即校验 payload 并记录,全部断言通过后统一汇总
  b.on('chat:user_joined', (data) => {
    try {
      assert(data.user_id === expectUserId, `user_joined.user_id=${data.user_id} 期望 ${expectUserId}`);
      assert(typeof data.nickname === 'string' && data.nickname.length > 0, 'user_joined.nickname 为空');
      assert('avatar_url' in data, 'user_joined 缺 avatar_url 字段');
      markReceived('chat:user_joined', true, data);
      console.log('[B] PASS chat:user_joined =', JSON.stringify(data));
    } catch (e) {
      console.log('[B] FAIL chat:user_joined:', e.message);
      markReceived('chat:user_joined', false, data);
    }
  });
  b.on('chat:typing', (data) => {
    try {
      assert(data.room_id === ROOM, `typing.room_id=${data.room_id} 期望 ${ROOM}`);
      assert(data.user_id === expectUserId, `typing.user_id=${data.user_id} 期望 ${expectUserId}`);
      assert(typeof data.nickname === 'string' && data.nickname.length > 0, 'typing.nickname 为空');
      markReceived('chat:typing', true, data);
      console.log('[B] PASS chat:typing =', JSON.stringify(data));
    } catch (e) {
      console.log('[B] FAIL chat:typing:', e.message);
      markReceived('chat:typing', false, data);
    }
  });
  b.on('chat:stop_typing', (data) => {
    try {
      assert(data.room_id === ROOM, `stop_typing.room_id=${data.room_id} 期望 ${ROOM}`);
      assert(data.user_id === expectUserId, `stop_typing.user_id=${data.user_id} 期望 ${expectUserId}`);
      markReceived('chat:stop_typing', true, data);
      console.log('[B] PASS chat:stop_typing =', JSON.stringify(data));
    } catch (e) {
      console.log('[B] FAIL chat:stop_typing:', e.message);
      markReceived('chat:stop_typing', false, data);
    }
  });
  b.on('chat:read', (data) => {
    try {
      assert(data.room_id === ROOM, `read.room_id=${data.room_id} 期望 ${ROOM}`);
      assert(data.message_id === MSG_ID, `read.message_id=${data.message_id} 期望 ${MSG_ID}`);
      assert(data.user_id === expectUserId, `read.user_id=${data.user_id} 期望 ${expectUserId}`);
      markReceived('chat:read', true, data);
      console.log('[B] PASS chat:read =', JSON.stringify(data));
    } catch (e) {
      console.log('[B] FAIL chat:read:', e.message);
      markReceived('chat:read', false, data);
    }
  });
  b.on('chat:message', (data) => {
    try {
      assert(data.room_id === ROOM, `message.room_id=${data.room_id} 期望 ${ROOM}`);
      assert(data.content === MSG_CONTENT, `message.content=${data.content} 期望 ${MSG_CONTENT}`);
      // 任务核心修复验证:user_id 为真实 userId(不再是 POC 版的 "unknown")
      assert(data.user_id === expectUserId, `message.user_id=${data.user_id} 期望真实 ${expectUserId}(非 unknown)`);
      markReceived('chat:message', true, data);
      console.log('[B] PASS chat:message =', JSON.stringify(data));
    } catch (e) {
      console.log('[B] FAIL chat:message:', e.message);
      markReceived('chat:message', false, data);
    }
  });

  // 事件流程:B 先入房、A 后入房(保证 B 能收到 A 触发的 chat:user_joined),
  // 随后 A 依次触发 typing/stop_typing/read/message,每步间隔留足广播到达时间
  b.emit('chat:join', ROOM);
  await sleep(300);
  a.emit('chat:join', ROOM);
  await sleep(300);
  a.emit('chat:typing', { room_id: ROOM });
  await sleep(300);
  a.emit('chat:stop_typing', { room_id: ROOM });
  await sleep(300);
  a.emit('chat:read', { room_id: ROOM, message_id: MSG_ID });
  await sleep(300);
  a.emit('chat:message', { room_id: ROOM, content: MSG_CONTENT });
  await sleep(2000); // 若期间事件未全部到达,汇总断言会给出缺失清单

  // 汇总断言:鉴权(C 被拒) + 5 个接收事件全部 PASS 才算成功
  const allReceived = RECEIVE_EVENTS.every((name) => received.get(name)?.ok === true);
  const missing = RECEIVE_EVENTS.filter((name) => !received.has(name));
  const failed = RECEIVE_EVENTS.filter((name) => received.get(name) && !received.get(name).ok);
  if (cRejected && allReceived) {
    console.log('AUTH PASS: 带 token 的 A/B 连接成功,无 token 的 C 被拒绝(connect_error)');
    console.log('POC PASS: chat 事件全集(join/leave/typing/stop_typing/read/message/user_joined)双向收发成功');
    process.exit(0);
  }
  if (!cRejected) console.error('AUTH FAIL: 无 token 的 C 未被拒绝');
  if (missing.length) console.error('POC FAIL: 以下事件未收到 →', missing.join(', '));
  failed.forEach((n) => console.error(`POC FAIL: ${n} payload 断言失败`));
  process.exit(1);
}

main().catch((e) => { console.error('POC ERROR:', e); process.exit(1); });
