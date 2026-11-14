//! 最小 HTTP 客户端：只够和 Coda 核心的 Web API 说话（GET/POST + JSON）。
//!
//! 不引 reqwest：GUI 只需要「拉一次 state、发一条 command」，多一个 TLS 栈只会拖慢编译。
//! 远端地址必须显式给出 http://host:port，本机默认 127.0.0.1。

use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

use serde_json::Value;

#[derive(Debug)]
pub enum HttpError {
    Io(String),
    Status(u16, String),
    Json(String),
}

impl std::fmt::Display for HttpError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            HttpError::Io(message) => write!(f, "网络错误: {message}"),
            HttpError::Status(code, body) => write!(f, "HTTP {code}: {body}"),
            HttpError::Json(message) => write!(f, "解析失败: {message}"),
        }
    }
}

fn split_base(base: &str) -> Result<(String, u16), HttpError> {
    let trimmed = base.trim().trim_end_matches('/');
    let without_scheme = trimmed.strip_prefix("http://").unwrap_or(trimmed);
    let (host, port) = match without_scheme.rsplit_once(':') {
        Some((host, port)) => (
            host.to_string(),
            port.parse::<u16>()
                .map_err(|_| HttpError::Io(format!("端口不合法: {port}")))?,
        ),
        None => (without_scheme.to_string(), 80),
    };
    if host.is_empty() {
        return Err(HttpError::Io("地址为空".to_string()));
    }
    Ok((host, port))
}

fn request(
    base: &str,
    token: &str,
    method: &str,
    path: &str,
    body: Option<&str>,
) -> Result<Value, HttpError> {
    let (host, port) = split_base(base)?;
    let address = format!("{host}:{port}");
    let addrs = address
        .to_socket_addrs()
        .map_err(|error| HttpError::Io(error.to_string()))?;
    let mut last_error = None;
    let mut stream = None;
    for addr in addrs {
        match TcpStream::connect_timeout(&addr, Duration::from_secs(4)) {
            Ok(socket) => {
                stream = Some(socket);
                break;
            }
            Err(error) => last_error = Some(error.to_string()),
        }
    }
    let mut stream = stream.ok_or_else(|| {
        HttpError::Io(last_error.unwrap_or_else(|| "连接失败".to_string()))
    })?;
    stream.set_read_timeout(Some(Duration::from_secs(8))).ok();
    stream.set_write_timeout(Some(Duration::from_secs(8))).ok();

    let payload = body.unwrap_or("");
    let request = format!(
        "{method} {path} HTTP/1.1\r\nHost: {host}:{port}\r\nAuthorization: Bearer {token}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{payload}",
        payload.as_bytes().len()
    );
    stream
        .write_all(request.as_bytes())
        .map_err(|error| HttpError::Io(error.to_string()))?;

    let mut raw = Vec::new();
    stream
        .read_to_end(&mut raw)
        .map_err(|error| HttpError::Io(error.to_string()))?;
    let text = String::from_utf8_lossy(&raw).into_owned();
    let (head, body) = text
        .split_once("\r\n\r\n")
        .ok_or_else(|| HttpError::Io("响应缺少头部".to_string()))?;
    let status = head
        .lines()
        .next()
        .and_then(|line| line.split_whitespace().nth(1))
        .and_then(|code| code.parse::<u16>().ok())
        .ok_or_else(|| HttpError::Io("响应缺少状态码".to_string()))?;

    if status == 401 {
        return Err(HttpError::Status(status, "配对令牌无效".to_string()));
    }
    if !(200..300).contains(&status) {
        return Err(HttpError::Status(status, body.chars().take(200).collect()));
    }
    serde_json::from_str(body).map_err(|error| HttpError::Json(error.to_string()))
}

pub fn get_state(base: &str, token: &str) -> Result<Value, HttpError> {
    let value = request(base, token, "GET", "/api/state", None)?;
    if value.get("ok").and_then(Value::as_bool) == Some(false) {
        return Err(HttpError::Status(200, "state 返回 ok=false".to_string()));
    }
    value
        .get("state")
        .cloned()
        .ok_or_else(|| HttpError::Json("缺少 state 字段".to_string()))
}

pub fn post_command(
    base: &str,
    token: &str,
    kind: &str,
    target: Value,
    payload: Value,
) -> Result<Value, HttpError> {
    let command_id = format!("gui-{}", unique_id());
    let body = serde_json::json!({
        "kind": kind,
        "target": target,
        "payload": payload,
        "commandId": command_id,
    });
    request(base, token, "POST", "/api/command", Some(&body.to_string()))
}

pub fn post_answer(
    base: &str,
    token: &str,
    interaction_id: &str,
    option_id: Option<&str>,
    text: Option<&str>,
) -> Result<Value, HttpError> {
    let body = serde_json::json!({
        "interactionId": interaction_id,
        "optionId": option_id,
        "text": text,
    });
    request(base, token, "POST", "/api/answer", Some(&body.to_string()))
}

fn unique_id() -> u128 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or(0)
}