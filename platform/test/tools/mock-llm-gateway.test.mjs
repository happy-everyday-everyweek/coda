/** 测试网关：三种协议的请求/流式响应形状。 */
import test from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";

import { createGateway, lastUserText, DEFAULT_REPLY } from "../../tools/mock-llm-gateway.mjs";

async function start() {
  const server = createGateway({ delayMs: 0 });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const base = `http://127.0.0.1:${server.address().port}`;
  return { server, base };
}

const readSse = async (response) => {
  const text = await response.text();
  return text
    .split("\n\n")
    .filter(Boolean)
    .map((block) => {
      const event = block.match(/^event: (.+)$/m)?.[1] ?? null;
      const data = block.match(/^data: (.+)$/m)?.[1];
      return { event, data: data === "[DONE]" ? "[DONE]" : JSON.parse(data) };
    });
};

test("网关：Anthropic Messages 非流式与流式", async (t) => {
  const { server, base } = await start();
  t.after(() => server.close());
  const body = { model: "claude-x", messages: [{ role: "user", content: "hi" }] };

  const plain = await fetch(`${base}/v1/messages`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  assert.equal(plain.status, 200);
  const message = await plain.json();
  assert.equal(message.type, "message");
  assert.equal(message.content[0].text, DEFAULT_REPLY);
  assert.equal(message.stop_reason, "end_turn");

  const stream = await fetch(`${base}/v1/messages`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ ...body, stream: true }),
  });
  const events = await readSse(stream);
  assert.equal(events[0].event, "message_start");
  assert.equal(events.at(-1).event, "message_stop");
  const text = events
    .filter((e) => e.event === "content_block_delta")
    .map((e) => e.data.delta.text)
    .join("");
  assert.equal(text, DEFAULT_REPLY);
});

test("网关：OpenAI Chat Completions 流式以 [DONE] 结束", async (t) => {
  const { server, base } = await start();
  t.after(() => server.close());
  const response = await fetch(`${base}/v1/chat/completions`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ model: "gpt-x", stream: true, messages: [{ role: "user", content: "hi" }] }),
  });
  const events = await readSse(response);
  assert.equal(events.at(-1).data, "[DONE]");
  const text = events
    .filter((e) => e.data !== "[DONE]")
    .map((e) => e.data.choices?.[0]?.delta?.content ?? "")
    .join("");
  assert.equal(text, DEFAULT_REPLY);
});

test("网关：OpenAI Responses 事件序列完整", async (t) => {
  const { server, base } = await start();
  t.after(() => server.close());
  const response = await fetch(`${base}/v1/responses`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      model: "gpt-5",
      stream: true,
      input: [{ role: "user", content: [{ type: "input_text", text: "hi" }] }],
    }),
  });
  const events = await readSse(response);
  const names = events.map((e) => e.event);
  for (const name of [
    "response.created",
    "response.output_item.added",
    "response.output_text.delta",
    "response.output_text.done",
    "response.output_item.done",
    "response.completed",
  ]) {
    assert.ok(names.includes(name), `缺少事件 ${name}`);
  }
  const completed = events.find((e) => e.event === "response.completed").data.response;
  assert.equal(completed.status, "completed");
  assert.equal(completed.output[0].content[0].text, DEFAULT_REPLY);
});

test("网关：Kimi registry 与模型 id", async (t) => {
  const { server, base } = await start();
  t.after(() => server.close());
  const registry = await (await fetch(`${base}/api.json`)).json();
  assert.equal(registry.coda.type, "openai");
  assert.equal(registry.coda.models["coda-gateway"].id, "coda-gateway");
  assert.match(registry.coda.api, /\/v1$/);
});

test("lastUserText：兼容三种请求体", () => {
  assert.equal(lastUserText({ messages: [{ role: "user", content: "a" }] }), "a");
  assert.equal(lastUserText({ messages: [{ role: "user", content: [{ type: "text", text: "b" }] }] }), "b");
  assert.equal(lastUserText({ input: [{ role: "user", content: [{ type: "input_text", text: "c" }] }] }), "c");
  assert.equal(lastUserText({ input: "d" }), "d");
});
