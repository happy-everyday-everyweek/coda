/** AGT-130：Android 宿主桥桌面客户端协议测试（假节点，不需要真机）。 */
import test from "node:test";
import assert from "node:assert/strict";
import net from "node:net";
import { once } from "node:events";

import { AndroidNodeClient, AndroidNodeError, NODE_ERRORS, NODE_METHODS } from "../../sync/android-node.mjs";

async function fakeNode({ token = "secret", reply = "pong" } = {}) {
  const sockets = new Set();
  const server = net.createServer((socket) => {
    sockets.add(socket);
    let authed = false;
    let buffer = "";
    const send = (obj) => socket.write(`${JSON.stringify(obj)}\n`);
    socket.setEncoding("utf8");
    socket.on("data", (chunk) => {
      buffer += chunk;
      for (let i = buffer.indexOf("\n"); i >= 0; i = buffer.indexOf("\n")) {
        const line = buffer.slice(0, i);
        buffer = buffer.slice(i + 1);
        if (!line.trim()) continue;
        const req = JSON.parse(line);
        if (req.method === NODE_METHODS.hello) {
          if (req.params?.token !== token) {
            send({ jsonrpc: "2.0", id: req.id, error: { code: NODE_ERRORS.UNAUTHORIZED, message: "invalid token" } });
            continue;
          }
          authed = true;
          send({
            jsonrpc: "2.0",
            id: req.id,
            result: {
              protocolVersion: 1,
              node: { id: "dev1", name: "Coda Android", appVersion: "0.8.4" },
              capabilities: { sessions: true, turns: true, streaming: true },
              sessions: [{ id: "s1", title: "demo", status: "idle" }],
            },
          });
          continue;
        }
        if (!authed) {
          send({ jsonrpc: "2.0", id: req.id, error: { code: NODE_ERRORS.UNAUTHORIZED, message: "hello required" } });
          continue;
        }
        if (req.method === NODE_METHODS.ping) send({ jsonrpc: "2.0", id: req.id, result: { pong: true } });
        else if (req.method === NODE_METHODS.snapshot) send({ jsonrpc: "2.0", id: req.id, result: { sessions: [{ id: "s1" }] } });
        else if (req.method === NODE_METHODS.turnSend) {
          send({ jsonrpc: "2.0", id: req.id, result: { ok: true } });
          send({ jsonrpc: "2.0", method: "node.event", params: { type: "message.delta", sessionId: req.params.sessionId, text: reply } });
        } else send({ jsonrpc: "2.0", id: req.id, result: { ok: true } });
      }
    });
  });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  return {
    port: server.address().port,
    close: () => {
      for (const socket of sockets) socket.destroy();
      return new Promise((resolve) => server.close(resolve));
    },
  };
}

test("Android 节点：hello 认证并返回能力/会话", async (t) => {
  const node = await fakeNode();
  t.after(node.close);
  const client = await AndroidNodeClient.connect({ host: "127.0.0.1", port: node.port, token: "secret" });
  t.after(() => client.close());
  assert.equal(client.node.id, "dev1");
  assert.equal(client.capabilities.turns, true);
  assert.equal(client.sessions[0].id, "s1");
  assert.deepEqual(await client.ping(), { pong: true });
  assert.deepEqual(await client.snapshot(), { sessions: [{ id: "s1" }] });
});

test("Android 节点：错误令牌被拒绝", async (t) => {
  const node = await fakeNode();
  t.after(node.close);
  await assert.rejects(
    AndroidNodeClient.connect({ host: "127.0.0.1", port: node.port, token: "wrong" }),
    (error) => error instanceof AndroidNodeError && error.code === NODE_ERRORS.UNAUTHORIZED,
  );
});

test("Android 节点：发送后收到流式事件", async (t) => {
  const node = await fakeNode({ reply: "hello from android" });
  t.after(node.close);
  const client = await AndroidNodeClient.connect({ host: "127.0.0.1", port: node.port, token: "secret" });
  t.after(() => client.close());
  const eventPromise = once(client, "event:message.delta");
  assert.deepEqual(await client.send("s1", "hi"), { ok: true });
  const [event] = await eventPromise;
  assert.equal(event.sessionId, "s1");
  assert.equal(event.text, "hello from android");
});
