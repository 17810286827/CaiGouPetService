// socket 事件全集验证脚本
// 覆盖:带真实 JWT 握手鉴权(A/B 成功、C 无 token 被拒) + chat 事件全集 + pet:interact(串门 ack/广播排除发送者/30s 冷却 reject)
// pet:interact 需要真实私聊房间(type=1),脚本经 mysql2 直连数据库自建 fixture(房间 id=900001,成员=测试用户+虚拟接收者)
// 运行:cd socket-poc && npm install && node test-socket.js(需注入 CAIGOPET_TOKEN=真实注册用户 JWT 与 DB_PASS)
const { io } = require('socket.io-client');
const mysql = require('mysql2/promise');

const SOCKET_URL = process.env.CAIGOPET_SOCKET_URL || 'http://localhost:3001';
// 调用方注入:真实注册用户 JWT(可 node -e 调 POST /api/auth/register 现取)
const AUTH_TOKEN = process.env.CAIGOPET_TOKEN;
const ROOM = 'room1';
const MSG_ID = 'msg-1001';       // chat:read 断言用 message_id
const MSG_CONTENT = 'hello-poc'; // chat:message 断言用消息内容

// pet:interact 场景:私聊房间 fixture id 与虚拟接收者 id(接收者无需真实 users 行,仅作成员占位)
const PET_ROOM_ID = 900001;
const PET_RECEIVER_ID = 900002;
// 数据库配置:与后端 application.yaml 对齐;DB_PASS 为敏感信息,强制由环境变量注入(不硬编码,缺失时 main() 提前退出)
const DB_CONFIG = {
  host: process.env.DB_HOST || '192.168.31.90',
  user: process.env.DB_USER || 'root',
  password: process.env.DB_PASS,
  database: 'caigoupet',
};

// 期望 B 收到的接收事件清单:全部收到且 payload 正确才算 PASS
const RECEIVE_EVENTS = ['chat:user_joined', 'chat:typing', 'chat:stop_typing', 'chat:read', 'chat:message'];
const received = new Map(); // 事件名 -> { ok, payload }

// C(无 token)是否收到了预期的 connect_error
let cRejected = false;
// pet:interact 断言标志:A 收到 ack、B 收到广播、A 二次发送收到 COOLDOWN reject
let petAckOk = false;
let petBroadcastOk = false;
let petCooldownOk = false;

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
    // auth 载荷携带 token(socket.io v4 规范);netty-socketio 2.0.13 在 engine.io 握手阶段
    // getAuthToken() 返回 null(POC 已验证),故同时用 query 传一份,服务端 resolveToken 双通道兼容
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

/**
 * 自建 pet:interact 需要的私聊房间 fixture:
 * 先清理上次运行残留(消息/成员/房间),再插入房间 + 两位成员(测试用户为创建者,虚拟接收者占位)
 * 服务端仅校验成员关系与接收者串门设置(缺省允许),故虚拟接收者无需真实 users 行
 * @param userId 测试用户 id(JWT 解析而来),作为房间创建者与发送者
 */
async function setupPetFixture(userId) {
  const db = await mysql.createConnection(DB_CONFIG);
  try {
    await db.execute(`DELETE FROM messages WHERE room_id = ?`, [PET_ROOM_ID]);
    await db.execute(`DELETE FROM chat_room_members WHERE room_id = ?`, [PET_ROOM_ID]);
    await db.execute(`DELETE FROM chat_rooms WHERE id = ?`, [PET_ROOM_ID]);
    // 房间 type=1 私聊;成员:测试用户(role=2 创建者) + 虚拟接收者(role=0 成员)
    await db.execute(`INSERT INTO chat_rooms (id, type, name, created_by) VALUES (?, 1, 'pet-fixture', ?)`,
      [PET_ROOM_ID, userId]);
    await db.execute(`INSERT INTO chat_room_members (room_id, user_id, role) VALUES (?, ?, 2)`,
      [PET_ROOM_ID, userId]);
    await db.execute(`INSERT INTO chat_room_members (room_id, user_id, role) VALUES (?, ?, 0)`,
      [PET_ROOM_ID, PET_RECEIVER_ID]);
    console.log(`[fixture] PASS 私聊房间 ${PET_ROOM_ID} 已就绪(成员: ${userId} + ${PET_RECEIVER_ID})`);
  } finally {
    await db.end();
  }
}

/** 清理 pet fixture,避免残留数据污染下次运行 */
async function cleanupPetFixture() {
  const db = await mysql.createConnection(DB_CONFIG);
  try {
    await db.execute(`DELETE FROM messages WHERE room_id = ?`, [PET_ROOM_ID]);
    await db.execute(`DELETE FROM chat_room_members WHERE room_id = ?`, [PET_ROOM_ID]);
    await db.execute(`DELETE FROM chat_rooms WHERE id = ?`, [PET_ROOM_ID]);
    console.log('[fixture] 清理完成');
  } finally {
    await db.end();
  }
}

async function main() {
  if (!AUTH_TOKEN) {
    console.error('AUTH FAIL: 未设置 CAIGOPET_TOKEN 环境变量(用注册用户的 JWT)');
    process.exit(1);
  }
  // DB_PASS 为数据库密码,禁止硬编码提交;pet:interact fixture 需直连数据库,缺失时直接退出
  if (!process.env.DB_PASS) {
    console.error('DB FAIL: 未设置 DB_PASS 环境变量(数据库密码,pet:interact fixture 需要直连 DB)');
    process.exit(1);
  }
  // 服务端事件回显的 user_id 应等于 token 中的真实 userId(任务核心修复点:不再恒为 "unknown")
  const expectUserId = parseJwt(AUTH_TOKEN).userId;

  // 预置 pet:interact 所需的私聊房间(DB 直连,失败则中止——后续 pet 断言无法进行)
  await setupPetFixture(expectUserId);

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
  // B 监听 pet:interact 广播:收到即校验 payload(串门 visit 首次发送 e1)
  b.on('pet:interact', (data) => {
    try {
      assert(data.room_id === PET_ROOM_ID, `pet:interact.room_id=${data.room_id} 期望 ${PET_ROOM_ID}`);
      assert(data.action_id === 'visit', `pet:interact.action_id=${data.action_id} 期望 visit`);
      assert(data.event_id === 'e1', `pet:interact.event_id=${data.event_id} 期望 e1`);
      assert(data.from && data.from.id === expectUserId, 'pet:interact.from.id 非发送者');
      assert(data.message && data.message.msg_type === 5, 'pet:interact.message 非系统消息(msg_type=5)');
      petBroadcastOk = true;
      console.log('[B] PASS pet:interact 广播(排除发送者) =', JSON.stringify(data));
    } catch (e) {
      console.log('[B] FAIL pet:interact 广播:', e.message);
      petBroadcastOk = false;
    }
  });
  // A 监听 pet:interact_ack:首次发送成功应有回执(cooldown_until)
  a.on('pet:interact_ack', (data) => {
    try {
      assert(data.event_id === 'e1', `pet:interact_ack.event_id=${data.event_id} 期望 e1`);
      assert(data.cooldown_until, 'pet:interact_ack 缺 cooldown_until');
      petAckOk = true;
      console.log('[A] PASS pet:interact_ack =', JSON.stringify(data));
    } catch (e) {
      console.log('[A] FAIL pet:interact_ack:', e.message);
      petAckOk = false;
    }
  });
  // A 监听 pet:interact_reject:第二次发送(e2)同一动作应在 30s 冷却内被拒(COOLDOWN + retry_after_ms)
  a.on('pet:interact_reject', (data) => {
    try {
      assert(data.event_id === 'e2', `pet:interact_reject.event_id=${data.event_id} 期望 e2`);
      assert(data.code === 'COOLDOWN', `pet:interact_reject.code=${data.code} 期望 COOLDOWN`);
      assert(typeof data.retry_after_ms === 'number' && data.retry_after_ms > 0,
        `pet:interact_reject 缺 retry_after_ms(收到 ${data.retry_after_ms})`);
      petCooldownOk = true;
      console.log('[A] PASS pet:interact_reject(COOLDOWN) =', JSON.stringify(data));
    } catch (e) {
      console.log('[A] FAIL pet:interact_reject:', e.message);
      petCooldownOk = false;
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
  await sleep(500);

  // pet:interact 流程:B 先入私聊房间、A 后入,随后 A 串门 visit(e1)→ 期望 A 收 ack + B 收广播(排除 A)
  b.emit('chat:join', String(PET_ROOM_ID));
  await sleep(300);
  a.emit('chat:join', String(PET_ROOM_ID));
  await sleep(300);
  a.emit('pet:interact', { room_id: PET_ROOM_ID, action_id: 'visit', client_event_id: 'e1' });
  await sleep(800);
  // 立即再发同动作(换 event_id=e2 避开幂等去重)→ 期望 30s 冷却内收到 COOLDOWN reject
  a.emit('pet:interact', { room_id: PET_ROOM_ID, action_id: 'visit', client_event_id: 'e2' });
  await sleep(1000);

  // 清理 fixture,避免残留数据影响下次运行
  await cleanupPetFixture();

  // 汇总断言:鉴权(C 被拒) + 5 个 chat 接收事件 + pet:interact 三项(ack/广播/冷却)全部 PASS 才算成功
  const allReceived = RECEIVE_EVENTS.every((name) => received.get(name)?.ok === true);
  const missing = RECEIVE_EVENTS.filter((name) => !received.has(name));
  const failed = RECEIVE_EVENTS.filter((name) => received.get(name) && !received.get(name).ok);
  const petOk = petAckOk && petBroadcastOk && petCooldownOk;
  if (cRejected && allReceived && petOk) {
    console.log('AUTH PASS: 带 token 的 A/B 连接成功,无 token 的 C 被拒绝(connect_error)');
    console.log('POC PASS: chat 事件全集 + pet:interact(串门 ack/广播排除发送者/30s 冷却 reject)双向收发成功');
    process.exit(0);
  }
  if (!cRejected) console.error('AUTH FAIL: 无 token 的 C 未被拒绝');
  if (missing.length) console.error('POC FAIL: 以下事件未收到 →', missing.join(', '));
  failed.forEach((n) => console.error(`POC FAIL: ${n} payload 断言失败`));
  if (!petAckOk) console.error('POC FAIL: A 未收到 pet:interact_ack');
  if (!petBroadcastOk) console.error('POC FAIL: B 未收到 pet:interact 广播');
  if (!petCooldownOk) console.error('POC FAIL: A 未收到 pet:interact_reject(COOLDOWN)');
  process.exit(1);
}

main().catch((e) => { console.error('POC ERROR:', e); process.exit(1); });
