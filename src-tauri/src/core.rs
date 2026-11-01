//! Managed desktop proxy-core adapter for Xray and sing-box. No shell invocation.
use serde::Serialize;
use serde_json::{json, Value};
use std::{
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    net::{TcpListener, TcpStream, ToSocketAddrs},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::Mutex,
    thread,
    time::{Duration, Instant},
};
use tauri::{AppHandle, Manager, State};

#[derive(Default)]
pub struct CoreState(pub Mutex<Managed>);

#[derive(Default)]
pub struct Managed {
    child: Option<Child>,
    config: Option<PathBuf>,
    log: Option<PathBuf>,
    mode: Option<String>,
    port: Option<u16>,
    started: Option<Instant>,
    verification: Option<String>,
    engine: Option<String>,
    last_error: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CoreStatus {
    running: bool,
    mode: Option<String>,
    port: Option<u16>,
    uptime_seconds: Option<u64>,
    verification: Option<String>,
    engine: Option<String>,
    error: Option<String>,
}

fn read_log(path: Option<&Path>) -> String {
    let Some(path) = path else { return String::new(); };
    let Ok(data) = fs::read(path) else { return String::new(); };
    let tail = &data[data.len().saturating_sub(12_000)..];
    String::from_utf8_lossy(tail).into_owned()
}

impl Managed {
    fn refresh(&mut self) {
        if let Some(child) = self.child.as_mut() {
            match child.try_wait() {
                Ok(Some(exit)) => {
                    let log = read_log(self.log.as_deref());
                    self.last_error = Some(format!("{} завершился ({exit}). {}", self.engine.as_deref().unwrap_or("Core"), log.trim()));
                    self.child.take();
                    self.clear_config();
                    self.port = None;
                    self.mode = None;
                    self.started = None;
                    self.verification = None;
                    self.engine = None;
                }
                Err(err) => self.last_error = Some(format!("Не удалось проверить процесс: {err}")),
                _ => {}
            }
        }
    }

    fn clear_config(&mut self) {
        if let Some(path) = self.config.take() { let _ = fs::remove_file(path); }
    }

    fn stop(&mut self) {
        if let Some(mut child) = self.child.take() {
            #[cfg(unix)] {
                // Give sing-box time to undo routes and firewall changes.
                unsafe { libc::kill(child.id() as i32, libc::SIGTERM); }
                let until = Instant::now() + Duration::from_secs(3);
                while Instant::now() < until {
                    if matches!(child.try_wait(), Ok(Some(_))) { break; }
                    thread::sleep(Duration::from_millis(60));
                }
            }
            if !matches!(child.try_wait(), Ok(Some(_))) { let _ = child.kill(); }
            let _ = child.wait();
        }
        self.clear_config();
        self.mode = None;
        self.port = None;
        self.started = None;
        self.verification = None;
        self.engine = None;
    }

    fn status(&mut self) -> CoreStatus {
        self.refresh();
        CoreStatus {
            running: self.child.is_some(),
            mode: self.mode.clone(),
            port: self.port,
            uptime_seconds: self.started.map(|x| x.elapsed().as_secs()),
            verification: self.verification.clone(),
            engine: self.engine.clone(),
            error: self.last_error.clone(),
        }
    }
}

pub fn stop_on_exit(app: &AppHandle) {
    if let Some(state) = app.try_state::<CoreState>() {
        if let Ok(mut managed) = state.0.lock() { managed.stop(); }
    }
}

fn binary_path(engine: &str, app: &AppHandle) -> Result<PathBuf, String> {
    let default_name = match (engine, cfg!(windows)) {
        ("xray", true) => "xray.exe",
        ("xray", false) => "xray",
        ("sing-box", true) => "sing-box.exe",
        ("sing-box", false) => "sing-box",
        _ => return Err("Неизвестное сетевое ядро".into()),
    };

    // Release bundles place both engines under the Tauri resource directory.
    if let Ok(bundled) = app.path().resolve(format!("binaries/{default_name}"), tauri::path::BaseDirectory::Resource) {
        if bundled.is_file() { return Ok(bundled); }
    }

    // Development builds may run from an unpacked application directory.
    if let Ok(executable) = std::env::current_exe() {
        if let Some(directory) = executable.parent() {
            for candidate in [directory.join("binaries").join(default_name), directory.join(default_name)] {
                if candidate.is_file() { return Ok(candidate); }
            }
        }
    }

    // PATH is a development fallback; arbitrary UI-supplied paths are not accepted.
    if let Some(search_path) = std::env::var_os("PATH") {
        for directory in std::env::split_paths(&search_path) {
            let candidate = directory.join(default_name);
            if candidate.is_file() { return Ok(candidate); }
        }
    }

    Err(format!("Не найдено встроенное ядро {default_name}. Переустановите Wayline или проверьте комплектность сборки."))
}

#[cfg(windows)]
fn hide_console(command: &mut Command) {
    use std::os::windows::process::CommandExt;
    command.creation_flags(0x08000000);
}
#[cfg(not(windows))]
fn hide_console(_command: &mut Command) {}

#[tauri::command]
pub fn core_ping(host: String, port: u16) -> Result<u64, String> {
    if host.is_empty() || host.chars().any(|c| c.is_control()) || port == 0 {
        return Err("Некорректный адрес или порт сервера".into());
    }
    let address = (host.as_str(), port).to_socket_addrs().map_err(|e| format!("DNS: {e}"))?
        .next().ok_or_else(|| "Адрес сервера не разрешился".to_string())?;
    let started = Instant::now();
    TcpStream::connect_timeout(&address, Duration::from_secs(3))
        .map_err(|e| format!("TCP: {e}"))?;
    Ok(started.elapsed().as_millis().min(u64::MAX as u128) as u64)
}

fn open_port() -> Result<u16, String> {
    let listener = TcpListener::bind(("127.0.0.1", 0)).map_err(|e| format!("Локальный порт: {e}"))?;
    listener.local_addr().map(|addr| addr.port()).map_err(|e| e.to_string())
}

fn routing_domains(settings: &Value, key: &str) -> Vec<String> {
    settings.get(key).and_then(Value::as_str).unwrap_or("")
        .split(|c: char| c == '\n' || c == ',' || c == ';')
        .map(str::trim).map(|s| s.trim_start_matches('.'))
        .filter(|s| !s.is_empty() && !s.chars().any(|c| c.is_whitespace() || c.is_control()))
        .map(str::to_string).collect()
}

fn create_singbox_config(outbound: Value, mode: &str, port: u16, routing: &Value) -> Result<Value, String> {
    const SUPPORTED: &[&str] = &["vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic", "anytls", "socks", "http"];
    let obj = outbound.as_object().ok_or("Некорректный outbound JSON")?;
    let protocol = obj.get("type").and_then(Value::as_str).ok_or("Отсутствует тип протокола")?;
    if !SUPPORTED.contains(&protocol) { return Err(format!("Протокол {protocol} не поддержан импортом")); }
    let host = obj.get("server").and_then(Value::as_str).unwrap_or("");
    let port_number = obj.get("server_port").and_then(Value::as_u64).unwrap_or(0);
    if host.is_empty() || port_number == 0 || port_number > 65535 { return Err("Укажите настоящий адрес и порт сервера".into()); }
    if host.chars().any(|c| c.is_control()) { return Err("Недопустимый адрес сервера".into()); }
    let mut outbound = outbound;
    outbound["tag"] = json!("proxy");
    let mut inbounds = vec![json!({
        "type":"mixed", "tag":"local", "listen":"127.0.0.1", "listen_port": port
    })];
    if mode == "tun" {
        inbounds.push(json!({
            "type":"tun", "tag":"vpn", "interface_name":"wayline0",
            "address":["172.19.0.1/30","fdfe:dcba:9876::1/126"], "mtu":1500, "auto_route":true,
            "strict_route":cfg!(target_os="windows"),
            "auto_redirect":cfg!(target_os="linux")
        }));
    }
    let enabled = routing.get("enabled").and_then(Value::as_bool).unwrap_or(true);
    let mut rules = vec![json!({"action":"sniff"}), json!({"protocol":"dns","action":"hijack-dns"})];
    if enabled && routing.get("bypassLan").and_then(Value::as_bool).unwrap_or(true) {
        rules.push(json!({"ip_is_private":true,"action":"route","outbound":"direct"}));
    }
    if enabled {
        for (key, outbound_tag) in [("directDomains", "direct"), ("proxyDomains", "proxy")] {
            let domains = routing_domains(routing, key);
            if !domains.is_empty() { rules.push(json!({"domain_suffix":domains,"action":"route","outbound":outbound_tag})); }
        }
        let blocked = routing_domains(routing, "blockDomains");
        if !blocked.is_empty() { rules.push(json!({"domain_suffix":blocked,"action":"reject"})); }
        if routing.get("blockAds").and_then(Value::as_bool).unwrap_or(false) { rules.push(json!({"rule_set":["geosite-category-ads-all"],"action":"reject"})); }
    }
    let dns_server = routing.get("dnsServer").and_then(Value::as_str).unwrap_or("cloudflare");
    let dns_ip = match dns_server { "google" => "8.8.8.8", "quad9" => "9.9.9.9", _ => "1.1.1.1" };
    let mut dns_servers = vec![json!({"type":"local","tag":"bootstrap"})];
    if dns_server == "system" { dns_servers.push(json!({"type":"local","tag":"remote"})); }
    else { dns_servers.push(json!({"type":"https","tag":"remote","server":dns_ip,"detour":"proxy"})); }
    let final_outbound = match routing.get("mode").and_then(Value::as_str).unwrap_or("rule") { "direct" => "direct", _ => "proxy" };
    let rule_sets = if enabled && routing.get("blockAds").and_then(Value::as_bool).unwrap_or(false) { vec![json!({"type":"remote","tag":"geosite-category-ads-all","format":"binary","url":"https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-category-ads-all.srs","download_detour":"proxy"})] } else { Vec::<Value>::new() };
    Ok(json!({
        "log":{"level":"info","timestamp":true},
        "dns":{"servers":dns_servers,"final":"remote","strategy":routing.get("dnsStrategy").and_then(Value::as_str).unwrap_or("prefer_ipv4")},
        "inbounds":inbounds,
        "outbounds":[outbound,{"type":"direct","tag":"direct"}],
        "route":{"auto_detect_interface":true,"default_domain_resolver":{"server":"bootstrap"},"rule_set":rule_sets,"final":final_outbound,"rules":rules}
    }))
}


fn create_xray_config(outbound: Value, port: u16, routing: &Value) -> Result<Value, String> {
    let protocol = outbound.get("type").and_then(Value::as_str).ok_or("Отсутствует тип протокола")?;
    let server = outbound.get("server").and_then(Value::as_str).unwrap_or("");
    let server_port = outbound.get("server_port").and_then(Value::as_u64).unwrap_or(0);
    if server.is_empty() || server_port == 0 || server_port > 65535 || server.chars().any(|c| c.is_control()) {
        return Err("Укажите корректный адрес и порт сервера".into());
    }
    let tls = outbound.get("tls").cloned().unwrap_or(json!({}));
    let tls_enabled = tls.get("enabled").and_then(Value::as_bool).unwrap_or(false);
    let server_name = tls.get("server_name").and_then(Value::as_str).unwrap_or(server);
    let mut stream = json!({"network":"raw","security":"none"});
    if tls_enabled {
        let reality = tls.get("reality").filter(|v| v.get("enabled").and_then(Value::as_bool)==Some(true));
        if let Some(r) = reality {
            stream["security"] = json!("reality");
            stream["realitySettings"] = json!({
                "serverName":server_name,
                "fingerprint":tls.get("utls").and_then(|v|v.get("fingerprint")).and_then(Value::as_str).unwrap_or("chrome"),
                "publicKey":r.get("public_key").and_then(Value::as_str).unwrap_or(""),
                "shortId":r.get("short_id").and_then(Value::as_str).unwrap_or(""),
                "spiderX":r.get("spider_x").and_then(Value::as_str).unwrap_or("/")
            });
        } else {
            stream["security"] = json!("tls");
            stream["tlsSettings"] = json!({
                "serverName":server_name,
                "alpn":tls.get("alpn").cloned().unwrap_or(json!([])),
                "fingerprint":tls.get("utls").and_then(|v|v.get("fingerprint")).and_then(Value::as_str).unwrap_or("chrome")
            });
        }
    }
    if let Some(transport) = outbound.get("transport") {
        match transport.get("type").and_then(Value::as_str).unwrap_or("tcp") {
            "ws" => {
                if stream["security"] == "reality" { return Err("Xray REALITY несовместим с WebSocket. Используйте RAW/TCP или gRPC.".into()); }
                stream["network"] = json!("ws");
                stream["wsSettings"] = json!({"path":transport.get("path").and_then(Value::as_str).unwrap_or("/"),"headers":transport.get("headers").cloned().unwrap_or(json!({}))});
            },
            "grpc" => {
                stream["network"] = json!("grpc");
                stream["grpcSettings"] = json!({"serviceName":transport.get("service_name").and_then(Value::as_str).unwrap_or("")});
            },
            "tcp" => {},
            other => return Err(format!("Xray-адаптер пока не поддерживает транспорт {other}")),
        }
    }
    let proxy = match protocol {
        "vless" => {
            let mut user = json!({
                "id": outbound.get("uuid").and_then(Value::as_str).unwrap_or(""),
                "encryption": "none"
            });
            if let Some(flow) = outbound.get("flow").and_then(Value::as_str).filter(|v| !v.is_empty()) {
                user["flow"] = json!(flow);
            }
            json!({
                "protocol": "vless",
                "settings": {
                    "vnext": [{
                        "address": server,
                        "port": server_port,
                        "users": [user]
                    }]
                },
                "streamSettings": stream
            })
        },
        "vmess" => json!({"protocol":"vmess","settings":{"vnext":[{"address":server,"port":server_port,"users":[{"id":outbound.get("uuid").and_then(Value::as_str).unwrap_or(""),"alterId":0,"security":outbound.get("security").and_then(Value::as_str).unwrap_or("auto")}]}]},"streamSettings":stream}),
        "trojan" => json!({"protocol":"trojan","settings":{"servers":[{"address":server,"port":server_port,"password":outbound.get("password").and_then(Value::as_str).unwrap_or("")}]},"streamSettings":stream}),
        "shadowsocks" => json!({"protocol":"shadowsocks","settings":{"servers":[{"address":server,"port":server_port,"password":outbound.get("password").and_then(Value::as_str).unwrap_or(""),"method":outbound.get("method").and_then(Value::as_str).unwrap_or("aes-128-gcm"),"uot":true}]},"streamSettings":stream}),
        "hysteria2" | "tuic" | "anytls" => return Err(format!("Протокол {protocol} не поддерживается Xray-core. Выберите sing-box.")),
        other => return Err(format!("Протокол {other} не поддерживается Xray-адаптером")),
    };
    let enabled = routing.get("enabled").and_then(Value::as_bool).unwrap_or(true);
    let mut rules = Vec::<Value>::new();
    if enabled && routing.get("bypassLan").and_then(Value::as_bool).unwrap_or(true) { rules.push(json!({"type":"field","ip":["geoip:private"],"outboundTag":"direct"})); }
    if enabled {
        for (key, tag) in [("directDomains", "direct"), ("proxyDomains", "proxy"), ("blockDomains", "block")] {
            let mut domains: Vec<String> = routing_domains(routing, key).into_iter().map(|d| format!("domain:{d}")).collect();
            if !domains.is_empty() { rules.push(json!({"type":"field","domain":domains,"outboundTag":tag})); }
        }
        if routing.get("blockAds").and_then(Value::as_bool).unwrap_or(false) { rules.push(json!({"type":"field","domain":["geosite:category-ads-all"],"outboundTag":"block"})); }
    }
    let dns_server = routing.get("dnsServer").and_then(Value::as_str).unwrap_or("cloudflare");
    let dns_address = match dns_server { "google" => "https://8.8.8.8/dns-query", "quad9" => "https://9.9.9.9/dns-query", "system" => "localhost", _ => "https://1.1.1.1/dns-query" };
    let final_tag = match routing.get("mode").and_then(Value::as_str).unwrap_or("rule") { "direct" => "direct", _ => "proxy" };
    rules.push(json!({"type":"field","network":"tcp,udp","outboundTag":final_tag}));
    let dns_strategy = match routing.get("dnsStrategy").and_then(Value::as_str).unwrap_or("ipv4_only") { "prefer_ipv6" => "UseIPv6", "prefer_ipv4" => "UseIP", _ => "UseIPv4" };
    Ok(json!({
        "log":{"loglevel":"warning"},
        "inbounds":[{"tag":"wayline-socks","listen":"127.0.0.1","port":port,"protocol":"socks","settings":{"auth":"noauth","udp":true,"ip":"127.0.0.1"}}],
        "outbounds":[proxy,{"protocol":"freedom","tag":"direct"},{"protocol":"blackhole","tag":"block","settings":{"response":{"type":"http"}}}],
        "dns":{"servers":[dns_address,"localhost"],"queryStrategy":dns_strategy},
        "routing":{"domainStrategy":"IPIfNonMatch","rules":rules}
    }))
}

fn xray_output_text(output: &std::process::Output) -> String {
    format!("{} {}", String::from_utf8_lossy(&output.stdout), String::from_utf8_lossy(&output.stderr))
}

fn xray_rejects_test_flag(message: &str) -> bool {
    let lower = message.to_lowercase();
    lower.contains("flag provided but not defined") && lower.contains("-test")
}

fn xray_command_syntax_error(message: &str) -> bool {
    let lower = message.to_lowercase();
    lower.contains("expected run command")
        || lower.contains("unknown command")
        || lower.contains("unknown shorthand flag")
        || lower.contains("unknown flag")
        || lower.contains("flag provided but not defined")
        || lower.contains("usage:")
}

fn run_xray_config_check(binary: &Path, config: &Path) -> std::io::Result<(Option<std::process::Output>, bool)> {
    // Xray CLI releases differ: recent versions use "xray run -test -config",
    // while older builds expose the default command as "xray -test -config".
    // Probe both full and short config flags before deciding how to launch.
    let attempts: [(&[&str], bool); 4] = [
        (&["run", "-test", "-config"], false),
        (&["run", "-test", "-c"], false),
        (&["-test", "-config"], true),
        (&["-test", "-c"], true),
    ];
    let mut unsupported_test_flag = false;
    let mut first_syntax_error: Option<(std::process::Output, bool)> = None;

    for (arguments, legacy_cli) in attempts {
        let mut command = Command::new(binary);
        hide_console(&mut command);
        for argument in arguments {
            command.arg(argument);
        }
        let output = command.arg(config).output()?;
        if output.status.success() {
            return Ok((Some(output), legacy_cli));
        }

        let message = xray_output_text(&output);
        if xray_rejects_test_flag(&message) {
            unsupported_test_flag = true;
            continue;
        }
        if xray_command_syntax_error(&message) {
            if first_syntax_error.is_none() {
                first_syntax_error = Some((output, legacy_cli));
            }
            continue;
        }

        // This is a real config/semantic error, not a CLI syntax mismatch.
        return Ok((Some(output), legacy_cli));
    }

    if unsupported_test_flag {
        // Some repackaged builds omit -test entirely. Let the actual process
        // startup validate the generated config and verify its SOCKS listener.
        return Ok((None, false));
    }
    if first_syntax_error.is_some() {
        // Do not fail the connection on a CLI usage mismatch in the optional
        // -test preflight. Start Xray with the canonical "run -c" form;
        // its real startup output and the SOCKS probe remain authoritative.
        return Ok((None, false));
    }
    Ok((None, false))
}

fn write_private(path: &Path, contents: &[u8]) -> Result<(), String> {
    let mut opts = OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)] {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    let mut file = opts.open(path).map_err(|e| format!("Файл конфигурации: {e}"))?;
    file.write_all(contents).map_err(|e| e.to_string())
}

// A SOCKS5 CONNECT to a public IP confirms an actual upstream connection.
// TCP handshake to localhost alone is not a VPN connectivity check.
fn verify_socks(port: u16) -> Result<(), String> {
    let mut stream = TcpStream::connect_timeout(
        &format!("127.0.0.1:{port}").parse().map_err(|e| format!("Адрес проверки: {e}"))?,
        Duration::from_secs(2),
    ).map_err(|e| format!("Локальный SOCKS недоступен: {e}"))?;
    stream.set_read_timeout(Some(Duration::from_secs(3))).map_err(|e| e.to_string())?;
    stream.set_write_timeout(Some(Duration::from_secs(3))).map_err(|e| e.to_string())?;
    stream.write_all(&[5, 1, 0]).map_err(|e| e.to_string())?;
    let mut response = [0u8; 2];
    stream.read_exact(&mut response).map_err(|e| e.to_string())?;
    if response != [5, 0] { return Err("SOCKS5 отклонил согласование".into()); }
    // CONNECT 1.1.1.1:443; this proves an outbound TCP path, not full DNS/UDP reachability.
    stream.write_all(&[5,1,0,1,1,1,1,1,1,0xBB]).map_err(|e| e.to_string())?;
    let mut reply = [0u8; 4];
    stream.read_exact(&mut reply).map_err(|e| e.to_string())?;
    if reply[0] != 5 || reply[1] != 0 {
        return Err(format!("Удалённый TCP CONNECT отклонён (SOCKS код {})", reply[1]));
    }
    Ok(())
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SubscriptionFetch {
    content: String,
    title: Option<String>,
}

/// HTTPS subscription import with TLS verification and a strict response size cap.
/// Provider tokens are never logged or returned in diagnostics.
#[tauri::command]
pub fn fetch_subscription(url: String) -> Result<SubscriptionFetch, String> {
    use std::net::IpAddr;
    let parsed = reqwest::Url::parse(&url).map_err(|_| "Некорректный URL подписки".to_string())?;
    if parsed.scheme() != "https" { return Err("Подписка должна использовать HTTPS".into()); }
    let host = parsed.host_str().ok_or("Нет адреса подписки")?;
    if host == "localhost" || host.ends_with(".localhost") || host.ends_with(".local") {
        return Err("Локальные адреса подписок запрещены".into());
    }
    if let Ok(ip) = host.parse::<IpAddr>() {
        let ok = match ip {
            IpAddr::V4(v) => !v.is_private() && !v.is_loopback() && !v.is_link_local() && !v.is_multicast() && !v.is_unspecified(),
            IpAddr::V6(v) => !v.is_loopback() && !v.is_unique_local() && !v.is_unicast_link_local() && !v.is_unspecified() && !v.is_multicast(),
        };
        if !ok { return Err("Частные IP-адреса подписок запрещены".into()); }
    }
    let client = reqwest::blocking::Client::builder()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(12))
        .build().map_err(|e| e.to_string())?;
    let response = client.get(parsed).send().map_err(|e| format!("Ошибка загрузки подписки: {e}"))?
        .error_for_status().map_err(|e| format!("Ответ сервера подписки: {e}"))?;
    const MAX_SIZE: usize = 2_000_000;
    if response.content_length().unwrap_or(0) > MAX_SIZE as u64 { return Err("Подписка превышает 2 МБ".into()); }
    let title = response.headers().get("profile-title")
        .or_else(|| response.headers().get("x-profile-title"))
        .and_then(|value| value.to_str().ok())
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_string)
        .or_else(|| response.headers().get(reqwest::header::CONTENT_DISPOSITION)
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.split("filename=").nth(1))
            .map(|value| value.trim_matches('"').trim_matches(';').trim().to_string())
            .filter(|value| !value.is_empty()));
    let mut bytes = Vec::new();
    response.take((MAX_SIZE + 1) as u64).read_to_end(&mut bytes).map_err(|e| e.to_string())?;
    if bytes.len() > MAX_SIZE { return Err("Подписка превышает 2 МБ".into()); }
    let content = String::from_utf8(bytes).map_err(|_| "Ответ подписки не UTF-8".to_string())?;
    Ok(SubscriptionFetch { content, title })
}

#[tauri::command]
pub fn core_status(state: State<'_, CoreState>) -> Result<CoreStatus, String> {
    let mut managed = state.0.lock().map_err(|e| e.to_string())?;
    Ok(managed.status())
}

#[tauri::command]
pub fn core_disconnect(state: State<'_, CoreState>) -> Result<CoreStatus, String> {
    let mut managed = state.0.lock().map_err(|e| e.to_string())?;
    managed.stop();
    managed.last_error = None;
    Ok(managed.status())
}

#[tauri::command]
pub fn core_log(state: State<'_, CoreState>) -> Result<String, String> {
    let managed = state.0.lock().map_err(|e| e.to_string())?;
    Ok(read_log(managed.log.as_deref()))
}

#[tauri::command]
pub fn core_connect(app: AppHandle, state: State<'_, CoreState>, outbound: Value, mode: String, engine: String, routing: Value) -> Result<CoreStatus, String> {
    if mode != "tun" && mode != "proxy" { return Err("Неизвестный режим подключения".into()); }
    if engine != "xray" && engine != "sing-box" { return Err("Неизвестное ядро".into()); }
    if engine == "xray" && mode == "tun" { return Err("Xray в Wayline пока работает в режиме локального SOCKS-прокси. Для TUN выберите sing-box.".into()); }
    let mut managed = state.0.lock().map_err(|e| e.to_string())?;
    managed.refresh();
    if managed.child.is_some() { return Err("Сначала отключите текущий сервер".into()); }
    managed.last_error = None;

    let binary = binary_path(&engine, &app)?;
    let port = open_port()?;
    let config = if engine == "xray" { create_xray_config(outbound, port, &routing)? } else { create_singbox_config(outbound, &mode, port, &routing)? };
    let dir = app.path().app_local_data_dir().map_err(|e| e.to_string())?;
    fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let config_path = dir.join(if engine == "xray" { "active-xray.json" } else { "active-sing-box.json" });
    let log_path = dir.join(if engine == "xray" { "xray.log" } else { "sing-box.log" });
    write_private(&config_path, &serde_json::to_vec_pretty(&config).map_err(|e| e.to_string())?)?;

    let (checked, xray_legacy_cli) = if engine == "xray" {
        match run_xray_config_check(&binary, &config_path) {
            Ok(value) => value,
            Err(err) => { let _ = fs::remove_file(&config_path); return Err(format!("Не удалось запустить {engine} ({}): {err}. Проверьте целостность установки и наличие встроенного ядра.", binary.display())); }
        }
    } else {
        let mut check = Command::new(&binary);
        hide_console(&mut check);
        let output = check.arg("check").arg("-c").arg(&config_path).output()
            .map_err(|err| format!("Не удалось запустить sing-box ({}): {err}", binary.display()))?;
        (Some(output), false)
    };
    if let Some(checked) = checked {
        if !checked.status.success() {
            let _ = fs::remove_file(&config_path);
            return Err(format!("{engine} config check: {} {}", String::from_utf8_lossy(&checked.stdout), String::from_utf8_lossy(&checked.stderr)));
        }
    }
    let log = File::create(&log_path).map_err(|e| e.to_string())?;
    let errlog = log.try_clone().map_err(|e| e.to_string())?;
    let mut command = Command::new(&binary);
    hide_console(&mut command);
    if engine == "xray" {
        // Xray resolves geoip.dat/geosite.dat relative to its asset directory.
        // Packaged builds keep the data files beside the executable in resources.
        if let Some(asset_dir) = binary.parent().filter(|path| !path.as_os_str().is_empty() && path.is_dir()) {
            command
                .env("XRAY_LOCATION_ASSET", asset_dir)
                .env("xray.location.asset", asset_dir)
                .current_dir(asset_dir);
        }
    }
    if engine == "xray" && xray_legacy_cli {
        command.arg("-config").arg(&config_path);
    } else if engine == "xray" {
        command.arg("run").arg("-c").arg(&config_path);
    } else {
        command.arg("run").arg("-c").arg(&config_path);
    }
    let child = command
        .stdout(Stdio::from(log)).stderr(Stdio::from(errlog))
        .stdin(Stdio::null())
        .spawn().map_err(|e| { let _ = fs::remove_file(&config_path); e.to_string() })?;

    managed.child = Some(child);
    managed.config = Some(config_path);
    managed.log = Some(log_path);
    managed.mode = Some(mode);
    managed.port = Some(port);
    managed.started = Some(Instant::now());
    managed.verification = Some("checking".into());
    managed.engine = Some(engine.clone());

    // Give the engine a chance to create an interface/listener before probing.
    let mut result = Err("Истекло время проверки SOCKS".to_string());
    let deadline = Instant::now() + Duration::from_secs(13);
    for _ in 0..8 {
        if Instant::now() >= deadline { break; }
        thread::sleep(Duration::from_millis(300));
        managed.refresh();
        if managed.child.is_none() { return Err(managed.last_error.clone().unwrap_or_else(|| format!("{engine} завершился"))); }
        match verify_socks(port) {
            Ok(()) => { result = Ok(()); break; },
            Err(error) => result = Err(error),
        }
    }
    // Process can be running when server is temporarily unavailable. Do not call that 'connected'.
    managed.verification = Some(if result.is_ok() { "verified" } else { "unverified" }.into());
    if let Err(error) = result { managed.last_error = Some(format!("Ядро работает, но удалённое TCP-соединение не подтверждено: {error}")); }
    Ok(managed.status())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn xray_cli_fallback_detects_expected_run_command() {
        assert!(xray_command_syntax_error("expected run command"));
        assert!(xray_command_syntax_error("flag provided but not defined: -test"));
        assert!(!xray_command_syntax_error("failed to load config: invalid UUID"));
    }

    #[test]
    fn xray_test_flag_fallback_is_specific() {
        assert!(xray_rejects_test_flag("flag provided but not defined: -test"));
        assert!(!xray_rejects_test_flag("expected run command"));
        assert!(!xray_rejects_test_flag("unknown flag: -config"));
    }

    #[test]
    fn tun_route_and_remote_are_generated() {
        let cfg = create_singbox_config(json!({"type":"vless","server":"vpn.example.com","server_port":443,"uuid":"abc"}), "tun", 2080).unwrap();
        assert_eq!(cfg["inbounds"][1]["type"], "tun");
        assert_eq!(cfg["inbounds"][1]["auto_route"], true);
        assert_eq!(cfg["outbounds"][0]["server"], "vpn.example.com");
        assert_eq!(cfg["route"]["auto_detect_interface"], true);
        assert_eq!(cfg["outbounds"][1]["tag"], "direct");
        assert_eq!(cfg["route"]["rules"][2]["ip_is_private"], true);
    }
    #[test]
    fn xray_generates_vless_reality_socks_config() {
        let outbound = json!({"type":"vless","server":"vpn.example.com","server_port":443,"uuid":"1234","flow":"xtls-rprx-vision","tls":{"enabled":true,"server_name":"example.com","utls":{"enabled":true,"fingerprint":"chrome"},"reality":{"enabled":true,"public_key":"pub","short_id":"abcd"}}});
        let cfg = create_xray_config(outbound, 2080).unwrap();
        assert_eq!(cfg["outbounds"][0]["protocol"], "vless");
        assert_eq!(cfg["outbounds"][0]["settings"]["vnext"][0]["address"], "vpn.example.com");
        assert_eq!(cfg["outbounds"][0]["settings"]["vnext"][0]["users"][0]["id"], "1234");
        assert_eq!(cfg["outbounds"][0]["settings"]["vnext"][0]["users"][0]["flow"], "xtls-rprx-vision");
        assert_eq!(cfg["outbounds"][0]["streamSettings"]["security"], "reality");
        assert_eq!(cfg["outbounds"][0]["streamSettings"]["realitySettings"]["publicKey"], "pub");
        assert_eq!(cfg["outbounds"][0]["streamSettings"]["network"], "raw");
        assert_eq!(cfg["inbounds"][0]["port"], 2080);
    }
    #[test]
    fn xray_rejects_hysteria2() {
        let outbound = json!({"type":"hysteria2","server":"vpn.example.com","server_port":443,"password":"secret"});
        assert!(create_xray_config(outbound, 2080).is_err());
    }
    #[test]
    fn rejects_bad_servers() {
        assert!(create_singbox_config(json!({"type":"vless","server":"","server_port":443}), "tun", 2080).is_err());
        assert!(create_singbox_config(json!({"type":"direct","server":"vpn.example.com","server_port":443}), "tun", 2080).is_err());
    }
    #[test]
    fn local_mode_is_not_tun() {
        let cfg = create_singbox_config(json!({"type":"trojan","server":"vpn.example.com","server_port":443}), "proxy", 2080).unwrap();
        assert_eq!(cfg["inbounds"].as_array().unwrap().len(), 1);
    }
}
