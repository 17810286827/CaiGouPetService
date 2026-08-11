// POC 验证脚本:双客户端连接 → 鉴权(空) → 双端 join 同房间 → A 发 chat:message → B 应收到
// 运行:cd socket-poc && npm install && node test-socket.js
const { io } = require('socket.io-client');

const SOCKET_URL = process.env.CAIGOPET_SOCKET_URL || 'http://localhost:3001';
const ROOM = 'room1';

function connectClient(label) {
  const socket = io(SOCKET_URL, {
    transports: ['websocket'], // 强制 websocket,跳过 http 长轮询,验证净 websocket 兼容
    auth: { token: 'poc-token' },
  });
  socket.on('connect', () => console.log(`[${label}] connected id=${socket.id}`));
  socket.on('connect_error', (err) => {
    console.error(`[${label}] connect_error:`, err.message);
    process.exit(1);
  });
  socket.on('disconnect', (r) => console.log(`[${label}] disconnected:`, r));
  return socket;
}

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

async function main() {
  const a = connectClient('A');
  const b = connectClient('B');
  await new Promise((resolve) => { a.on('connect', resolve); });

  // B 监听广播
  b.on('chat:message', (data) => {
    console.log('[B] received chat:message =', JSON.stringify(data));
    if (data.room_id === ROOM && data.content === 'hello-poc') {
      console.log('POC PASS: 房间广播双向收发成功');
      process.exit(0);
    } else {
      console.log('POC FAIL: 内容不匹配');
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
