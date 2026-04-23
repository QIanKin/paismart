// PaiSmart - 千瓜/小红书 浏览器自动化 CDP 代理
//
// 为什么需要这个代理：
//   Chrome 147+ 的 --remote-debugging-port 出于安全考虑只接受
//   来自 127.0.0.1 的连接、且 Host 头必须是 localhost/127.0.0.1。
//   Docker 容器通过 host.docker.internal 访问宿主时，源 IP/Host 都不符合，
//   因此 Chrome 会直接 403/关闭连接。
//
// 本代理在宿主机监听 0.0.0.0:9223（容器可通过 host.docker.internal:9223 访问），
// 并将连接转发到 127.0.0.1:9222（Chrome），同时：
//   - 改写 HTTP 请求里的 Host 头为 localhost:9222
//   - WebSocket Upgrade 握手同样改写
//   - 升级后的原始帧直接透传
//
// 用法：node cdp-proxy.mjs   （默认 9223 -> 9222，可用环境变量覆盖）

import net from 'node:net';

const LISTEN_PORT = Number(process.env.CDP_PROXY_LISTEN_PORT || 9223);
const LISTEN_HOST = process.env.CDP_PROXY_LISTEN_HOST || '0.0.0.0';
const TARGET_PORT = Number(process.env.CDP_TARGET_PORT || 9222);
const TARGET_HOST = process.env.CDP_TARGET_HOST || '127.0.0.1';
const REWRITE_HOST = process.env.CDP_REWRITE_HOST || `localhost:${TARGET_PORT}`;

function rewriteHostHeader(buf) {
  const text = buf.toString('latin1');
  const headerEnd = text.indexOf('\r\n\r\n');
  if (headerEnd < 0) return null;
  const head = text.slice(0, headerEnd);
  const rest = text.slice(headerEnd);
  const newHead = head.replace(/\r\nHost:[^\r\n]*/i, `\r\nHost: ${REWRITE_HOST}`);
  return Buffer.from(newHead + rest, 'latin1');
}

const server = net.createServer((client) => {
  const clientAddr = `${client.remoteAddress}:${client.remotePort}`;
  console.log(`[conn] ${clientAddr} opened`);
  const upstream = net.connect(TARGET_PORT, TARGET_HOST);
  let headerRewritten = false;
  let pending = Buffer.alloc(0);
  let upstreamReady = false;
  let upstreamBuf = Buffer.alloc(0);

  upstream.on('connect', () => {
    upstreamReady = true;
    if (upstreamBuf.length > 0) {
      upstream.write(upstreamBuf);
      upstreamBuf = Buffer.alloc(0);
    }
  });

  const sendUpstream = (data) => {
    if (upstreamReady) upstream.write(data);
    else upstreamBuf = Buffer.concat([upstreamBuf, data]);
  };

  client.on('data', (chunk) => {
    if (headerRewritten) {
      sendUpstream(chunk);
      return;
    }
    pending = Buffer.concat([pending, chunk]);
    if (pending.indexOf('\r\n\r\n') >= 0) {
      const rewritten = rewriteHostHeader(pending) || pending;
      console.log(`[conn] ${clientAddr} header: ${pending.slice(0, pending.indexOf('\r\n\r\n')).toString('latin1').split('\r\n')[0]}`);
      sendUpstream(rewritten);
      headerRewritten = true;
      pending = Buffer.alloc(0);
    } else if (pending.length > 64 * 1024) {
      sendUpstream(pending);
      headerRewritten = true;
      pending = Buffer.alloc(0);
    }
  });

  upstream.on('data', (chunk) => client.write(chunk));

  const cleanup = (who) => {
    console.log(`[conn] ${clientAddr} closed by ${who}`);
    client.destroy();
    upstream.destroy();
  };
  client.on('close', () => cleanup('client'));
  upstream.on('close', () => cleanup('upstream'));
  client.on('error', (e) => console.warn(`[client ${clientAddr}] ${e.message}`));
  upstream.on('error', (e) => console.warn(`[upstream ${clientAddr}] ${e.message}`));
});

server.listen(LISTEN_PORT, LISTEN_HOST, () => {
  console.log(`[cdp-proxy] listening on ${LISTEN_HOST}:${LISTEN_PORT} -> ${TARGET_HOST}:${TARGET_PORT}`);
  console.log(`[cdp-proxy] rewriting Host header to "${REWRITE_HOST}"`);
});

server.on('error', (e) => {
  console.error(`[cdp-proxy] server error: ${e.message}`);
  process.exit(1);
});
