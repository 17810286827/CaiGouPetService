// socket 鉴权验证脚本:带真实 JWT 的 A/B 应连接成功,无 token 的 C 应被拒绝(connect_error)
// 运行:cd socket-poc && npm install && node test-socket.js(需注入 CAIGOPET_TOKEN=真实注册用户 JWT)
const { io } = require('socket.io-client');

const SOCKET_URL = process.env.CAIGOPET_SOCKET_URL || 'http://localhost:3001';
// 调用方注入:真实注册用户 JWT(可 node -e 调 POST /api/auth/register 现取)
const AUTH_TOKEN = process.env.CAIGOPET_TOKEN;
const ROOM = 'room1';

// C(无 token)是否收到了预期的 connect_error
let cRejected = false;

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

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

async function main() {
  if (!AUTH_TOKEN) {
    console.error('AUTH FAIL: 未设置 CAIGOPET_TOKEN 环境变量(用注册用户的 JWT)');
    process.exit(1);
  }

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

  // B 监听广播
  b.on('chat:message', (data) => {
    console.log('[B] received chat:message =', JSON.stringify(data));
    if (data.room_id === ROOM && data.content === 'hello-poc' && cRejected) {
      console.log('AUTH PASS: 带 token 的 A/B 连接成功,无 token 的 C 被拒绝(connect_error)');
      console.log('POC PASS: 房间广播双向收发成功');
      process.exit(0);
    } else {
      console.log('POC FAIL: 房间广播内容不匹配或 C 未被拒绝');
      process.exit(1);
    }
  });

  // 双端都 join 同房间
  a.emit('chat:join', ROOM);
  b.emit('chat:join', ROOM);
  await sleep(300);

  // A 发消息,B 应收到广播
  a.emit('chat:message', { room_id: ROOM, content: 'hello-poc' });
  await sleep(3000); // 若 3 秒内 B 未收到,脚本卡住则视为失败

  console.log('POC FAIL: 3 秒内未收到广播');
  process.exit(1);
}

main().catch((e) => { console.error('POC ERROR:', e); process.exit(1); });
