//! Managed desktop core adapter for Xray and sing-box with platform proxy restoration.
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
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
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
    proxy_backup: Option<PathBuf>,
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
        if let Some(path) = self.proxy_backup.take() { let _ = restore_system_proxy(&path); }
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


#[cfg(windows)]
fn request_windows_tun_elevation(app: &AppHandle, outbound: &Value, mode: &str, engine: &str, routing: &Value) -> Result<(), String> {
    use std::{ffi::OsStr, os::windows::ffi::OsStrExt, ptr::null_mut};
    #[link(name = "shell32")]
    extern "system" {
        fn IsUserAnAdmin() -> i32;
        fn ShellExecuteW(hwnd: *mut std::ffi::c_void, operation: *const u16, file: *const u16,
            parameters: *const u16, directory: *const u16, show: i32) -> isize;
    }
    if unsafe { IsUserAnAdmin() } != 0 { return Ok(()); }
    let data_dir = app.path().app_local_data_dir().map_err(|e| e.to_string())?;
    fs::create_dir_all(&data_dir).map_err(|e| e.to_string())?;
    let pending = data_dir.join("pending-connect.json");
    let payload = json!({"outbound":outbound,"mode":mode,"engine":engine,"routing":routing});
    fs::write(&pending, serde_json::to_vec(&payload).map_err(|e| e.to_string())?).map_err(|e| e.to_string())?;
    let executable = std::env::current_exe().map_err(|e| e.to_string())?;
    let verb: Vec<u16> = OsStr::new("runas").encode_wide().chain(Some(0)).collect();
    let file: Vec<u16> = executable.as_os_str().encode_wide().chain(Some(0)).collect();
    let result = unsafe { ShellExecuteW(null_mut(), verb.as_ptr(), file.as_ptr(), null_mut(), null_mut(), 1) };
    if result <= 32 {
        let _ = fs::remove_file(&pending);
        return Err("Windows не выдал права администратора. Повторите запуск TUN и подтвердите запрос UAC.".into());
    }
    app.exit(0);
    Err("Запрашиваю права администратора для TUN. Wayline перезапустится и продолжит подключение.".into())
}

#[cfg(not(windows))]
fn request_windows_tun_elevation(_app: &AppHandle, _outbound: &Value, _mode: &str, _engine: &str, _routing: &Value) -> Result<(), String> {
    Ok(())
}

#[tauri::command]
pub fn core_take_pending_connect(app: AppHandle) -> Result<Option<Value>, String> {
    let path = app.path().app_local_data_dir().map_err(|e| e.to_string())?.join("pending-connect.json");
    if !path.is_file() { return Ok(None); }
    let bytes = fs::read(&path).map_err(|e| e.to_string())?;
    let _ = fs::remove_file(&path);
    serde_json::from_slice(&bytes).map(Some).map_err(|e| format!("Повреждён запрос подключения после повышения прав: {e}"))
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
fn run_hidden_powershell(script: &str, backup: &Path) -> Result<(), String> {
    let mut command = Command::new("powershell.exe");
    command.args(["-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script])
        .env("WAYLINE_PROXY_BACKUP", backup);
    use std::os::windows::process::CommandExt;
    let output = command.creation_flags(0x08000000).output().map_err(|e| format!("Не удалось обновить системный прокси: {e}"))?;
    if !output.status.success() {
        return Err(String::from_utf8_lossy(&output.stderr).trim().to_string().if_empty("Не удалось изменить системный прокси"));
    }
    Ok(())
}

#[cfg(windows)]
fn enable_windows_system_proxy(port: u16, engine: &str, backup: &Path) -> Result<(), String> {
    let proxy_server = if engine == "xray" { format!("socks=127.0.0.1:{port}") } else { format!("http=127.0.0.1:{port};https=127.0.0.1:{port};socks=127.0.0.1:{port}") };
    let script = format!(r#"
$ErrorActionPreference = 'Stop'
$p = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
if (-not (Test-Path $env:WAYLINE_PROXY_BACKUP)) {{
  $v = Get-ItemProperty -Path $p
  $s = [ordered]@{{
    ProxyEnablePresent = ($v.PSObject.Properties.Name -contains 'ProxyEnable')
    ProxyEnable = $v.ProxyEnable
    ProxyServerPresent = ($v.PSObject.Properties.Name -contains 'ProxyServer')
    ProxyServer = $v.ProxyServer
    ProxyOverridePresent = ($v.PSObject.Properties.Name -contains 'ProxyOverride')
    ProxyOverride = $v.ProxyOverride
    AutoConfigURLPresent = ($v.PSObject.Properties.Name -contains 'AutoConfigURL')
    AutoConfigURL = $v.AutoConfigURL
  }}
  $s | ConvertTo-Json | Set-Content -LiteralPath $env:WAYLINE_PROXY_BACKUP -Encoding UTF8
}}
New-ItemProperty -Path $p -Name ProxyEnable -PropertyType DWord -Value 1 -Force | Out-Null
New-ItemProperty -Path $p -Name ProxyServer -PropertyType String -Value '{proxy_server}' -Force | Out-Null
New-ItemProperty -Path $p -Name ProxyOverride -PropertyType String -Value 'localhost;127.*;10.*;172.*;192.168.*;<local>' -Force | Out-Null
Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class WaylineWinInet {{ [DllImport("wininet.dll")] public static extern bool InternetSetOption(IntPtr h, int o, IntPtr b, int l); }}' -ErrorAction SilentlyContinue
[WaylineWinInet]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0) | Out-Null
[WaylineWinInet]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0) | Out-Null
"#);
    run_hidden_powershell(&script, backup)
}

#[cfg(windows)]
fn restore_windows_system_proxy(backup: &Path) -> Result<(), String> {
    if !backup.is_file() { return Ok(()); }
    let script = r#"
$ErrorActionPreference = 'Stop'
$p = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
if (Test-Path $env:WAYLINE_PROXY_BACKUP) {
  $s = Get-Content -LiteralPath $env:WAYLINE_PROXY_BACKUP -Raw | ConvertFrom-Json
  if ([bool]$s.ProxyEnablePresent) { New-ItemProperty -Path $p -Name ProxyEnable -PropertyType DWord -Value ([int]$s.ProxyEnable) -Force | Out-Null } else { Remove-ItemProperty -Path $p -Name ProxyEnable -ErrorAction SilentlyContinue }
  if ([bool]$s.ProxyServerPresent) { New-ItemProperty -Path $p -Name ProxyServer -PropertyType String -Value ([string]$s.ProxyServer) -Force | Out-Null } else { Remove-ItemProperty -Path $p -Name ProxyServer -ErrorAction SilentlyContinue }
  if ([bool]$s.ProxyOverridePresent) { New-ItemProperty -Path $p -Name ProxyOverride -PropertyType String -Value ([string]$s.ProxyOverride) -Force | Out-Null } else { Remove-ItemProperty -Path $p -Name ProxyOverride -ErrorAction SilentlyContinue }
  if ([bool]$s.AutoConfigURLPresent) { New-ItemProperty -Path $p -Name AutoConfigURL -PropertyType String -Value ([string]$s.AutoConfigURL) -Force | Out-Null } else { Remove-ItemProperty -Path $p -Name AutoConfigURL -ErrorAction SilentlyContinue }
  Remove-Item -LiteralPath $env:WAYLINE_PROXY_BACKUP -Force
}
Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class WaylineWinInetRestore { [DllImport("wininet.dll")] public static extern bool InternetSetOption(IntPtr h, int o, IntPtr b, int l); }' -ErrorAction SilentlyContinue
[WaylineWinInetRestore]::InternetSetOption([IntPtr]::Zero, 39, [IntPtr]::Zero, 0) | Out-Null
[WaylineWinInetRestore]::InternetSetOption([IntPtr]::Zero, 37, [IntPtr]::Zero, 0) | Out-Null
"#;
    run_hidden_powershell(script, backup)
}

#[cfg(not(windows))]
fn enable_windows_system_proxy(_port: u16, _engine: &str, _backup: &Path) -> Result<(), String> { Ok(()) }

#[cfg(not(windows))]
fn restore_windows_system_proxy(_backup: &Path) -> Result<(), String> { Ok(()) }


#[cfg(target_os = "linux")]
fn find_executable(name: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    std::env::split_paths(&path).map(|dir| dir.join(name)).find(|candidate| candidate.is_file())
}

#[cfg(target_os = "linux")]
fn gsettings_get(schema: &str, key: &str) -> Result<String, String> {
    let output = Command::new("gsettings").args(["get", schema, key]).output()
        .map_err(|e| format!("Не найден gsettings: {e}"))?;
    if !output.status.success() { return Err(format!("GNOME не поддерживает настройку {schema}.{key}")); }
    Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
}

#[cfg(target_os = "linux")]
fn gsettings_set(schema: &str, key: &str, value: &str) -> Result<(), String> {
    let output = Command::new("gsettings").args(["set", schema, key, value]).output()
        .map_err(|e| format!("Не удалось вызвать gsettings: {e}"))?;
    if !output.status.success() { return Err(String::from_utf8_lossy(&output.stderr).trim().to_string()); }
    Ok(())
}

#[cfg(target_os = "linux")]
fn run_kde_config(tool: &Path, config: &Path, key: &str, value: &str) -> Result<(), String> {
    let output = Command::new(tool)
        .args(["--file"]).arg(config)
        .args(["--group", "Proxy Settings", "--key", key, value])
        .output().map_err(|e| format!("Не удалось запустить {}: {e}", tool.display()))?;
    if !output.status.success() {
        return Err(String::from_utf8_lossy(&output.stderr).trim().to_string().if_empty("kwriteconfig завершился с ошибкой"));
    }
    Ok(())
}

#[cfg(target_os = "linux")]
fn notify_kde_proxy_changed() -> Result<(), String> {
    let dbus = find_executable("dbus-send").ok_or("Не найден dbus-send для обновления настроек KDE")?;
    let output = Command::new(dbus)
        .args(["--type=signal", "/KIO/Scheduler", "org.kde.KIO.Scheduler.reparseSlaveConfiguration", "string:''"])
        .output().map_err(|e| e.to_string())?;
    if !output.status.success() {
        return Err(String::from_utf8_lossy(&output.stderr).trim().to_string().if_empty("KDE не принял обновление прокси"));
    }
    Ok(())
}

#[cfg(target_os = "linux")]
fn enable_linux_system_proxy(port: u16, engine: &str, backup: &Path) -> Result<(), String> {
    let desktop = std::env::var("XDG_CURRENT_DESKTOP").unwrap_or_default().to_ascii_uppercase();
    let is_kde = desktop.contains("KDE") || desktop.contains("TRINITY");
    let kde_tool = if is_kde {
        ["kwriteconfig6", "kwriteconfig5", "kwriteconfig"].iter().find_map(|name| find_executable(name))
    } else { None };
    let gsettings_available = find_executable("gsettings").is_some();
    let mut saved = serde_json::Map::new();
    let entries = [
        ("org.gnome.system.proxy", "mode"),
        ("org.gnome.system.proxy", "use-same-proxy"),
        ("org.gnome.system.proxy.socks", "host"),
        ("org.gnome.system.proxy.socks", "port"),
        ("org.gnome.system.proxy.http", "host"),
        ("org.gnome.system.proxy.http", "port"),
        ("org.gnome.system.proxy.https", "host"),
        ("org.gnome.system.proxy.https", "port"),
    ];
    let mut use_gsettings = false;
    if gsettings_available {
        for (schema, key) in entries {
            match gsettings_get(schema, key) {
                Ok(value) => { saved.insert(format!("{schema}|{key}"), json!(value)); use_gsettings = true; }
                Err(error) => {
                    if kde_tool.is_none() { return Err(error); }
                    saved.clear();
                    use_gsettings = false;
                    break;
                }
            }
        }
    }
    if !use_gsettings && kde_tool.is_none() {
        return Err("Не найдены настройки системного прокси: установите gsettings для GNOME или kwriteconfig для KDE".into());
    }

    let config_home = std::env::var_os("XDG_CONFIG_HOME").map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|home| PathBuf::from(home).join(".config")))
        .ok_or("Не удалось определить каталог пользовательских настроек Linux")?;
    let kde_config = config_home.join("kioslaverc");
    let kde_backup = backup.with_file_name("kioslaverc.original");
    let kde_existed = kde_tool.is_some() && kde_config.is_file();
    if kde_tool.is_some() {
        if kde_existed { fs::copy(&kde_config, &kde_backup).map_err(|e| format!("Не удалось сохранить исходный kioslaverc: {e}"))?; }
        else { let _ = fs::remove_file(&kde_backup); }
        saved.insert("kdeConfig".into(), json!(kde_config.to_string_lossy()));
        saved.insert("kdeBackup".into(), json!(kde_backup.to_string_lossy()));
        saved.insert("kdeExisted".into(), json!(kde_existed));
    }
    if !backup.is_file() {
        write_private(backup, &serde_json::to_vec(&Value::Object(saved)).map_err(|e| e.to_string())?)?;
    }

    if use_gsettings {
        gsettings_set("org.gnome.system.proxy", "mode", "'manual'")?;
        gsettings_set("org.gnome.system.proxy", "use-same-proxy", "false")?;
        gsettings_set("org.gnome.system.proxy.socks", "host", "'127.0.0.1'")?;
        gsettings_set("org.gnome.system.proxy.socks", "port", &port.to_string())?;
        if engine == "xray" {
            gsettings_set("org.gnome.system.proxy.http", "host", "''")?;
            gsettings_set("org.gnome.system.proxy.http", "port", "0")?;
            gsettings_set("org.gnome.system.proxy.https", "host", "''")?;
            gsettings_set("org.gnome.system.proxy.https", "port", "0")?;
        } else {
            gsettings_set("org.gnome.system.proxy.http", "host", "'127.0.0.1'")?;
            gsettings_set("org.gnome.system.proxy.http", "port", &port.to_string())?;
            gsettings_set("org.gnome.system.proxy.https", "host", "'127.0.0.1'")?;
            gsettings_set("org.gnome.system.proxy.https", "port", &port.to_string())?;
        }
    }

    if let Some(tool) = kde_tool {
        let address = format!("127.0.0.1 {port}");
        for key in ["httpProxy", "httpsProxy", "ftpProxy"] {
            let value = if engine == "xray" { String::new() } else { format!("http://{address}") };
            run_kde_config(&tool, &kde_config, key, &value)?;
        }
        run_kde_config(&tool, &kde_config, "socksProxy", &format!("socks://{address}"))?;
        run_kde_config(&tool, &kde_config, "ProxyType", "1")?;
        notify_kde_proxy_changed()?;
    }
    Ok(())
}

#[cfg(target_os = "linux")]
fn restore_linux_system_proxy(backup: &Path) -> Result<(), String> {
    if !backup.is_file() { return Ok(()); }
    let saved: Value = serde_json::from_slice(&fs::read(backup).map_err(|e| e.to_string())?)
        .map_err(|e| format!("Повреждена резервная копия системного прокси: {e}"))?;
    let mut errors = Vec::new();
    if find_executable("gsettings").is_some() {
        for key in ["mode", "use-same-proxy"] {
            if let Some(value) = saved.get(&format!("org.gnome.system.proxy|{key}")).and_then(Value::as_str) {
                if let Err(error) = gsettings_set("org.gnome.system.proxy", key, value) { errors.push(error); }
            }
        }
        for schema in ["org.gnome.system.proxy.socks", "org.gnome.system.proxy.http", "org.gnome.system.proxy.https"] {
            for key in ["host", "port"] {
                if let Some(value) = saved.get(&format!("{schema}|{key}")).and_then(Value::as_str) {
                    if let Err(error) = gsettings_set(schema, key, value) { errors.push(error); }
                }
            }
        }
    }
    if let (Some(config), Some(backup_copy)) = (
        saved.get("kdeConfig").and_then(Value::as_str),
        saved.get("kdeBackup").and_then(Value::as_str),
    ) {
        let config = PathBuf::from(config);
        let backup_copy = PathBuf::from(backup_copy);
        let existed = saved.get("kdeExisted").and_then(Value::as_bool).unwrap_or(false);
        let restore = if existed {
            fs::copy(&backup_copy, &config).map(|_| ()).map_err(|e| format!("Не удалось восстановить kioslaverc: {e}"))
        } else {
            fs::remove_file(&config).or_else(|e| if e.kind() == std::io::ErrorKind::NotFound { Ok(0) } else { Err(e) }).map(|_| ())
                .map_err(|e| format!("Не удалось удалить временный kioslaverc: {e}"))
        };
        if let Err(error) = restore { errors.push(error); }
        if let Err(error) = notify_kde_proxy_changed() { errors.push(error); }
        if errors.is_empty() { let _ = fs::remove_file(backup_copy); }
    }
    if !errors.is_empty() { return Err(errors.join("; ")); }
    fs::remove_file(backup).map_err(|e| e.to_string())?;
    Ok(())
}

#[cfg(windows)]
fn enable_system_proxy(port: u16, engine: &str, backup: &Path) -> Result<(), String> {
    enable_windows_system_proxy(port, engine, backup)
}
#[cfg(target_os = "linux")]
fn enable_system_proxy(port: u16, engine: &str, backup: &Path) -> Result<(), String> {
    enable_linux_system_proxy(port, engine, backup)
}
#[cfg(not(any(windows, target_os = "linux")))]
fn enable_system_proxy(_port: u16, _engine: &str, _backup: &Path) -> Result<(), String> { Ok(()) }

#[cfg(windows)]
fn restore_system_proxy(backup: &Path) -> Result<(), String> { restore_windows_system_proxy(backup) }
#[cfg(target_os = "linux")]
fn restore_system_proxy(backup: &Path) -> Result<(), String> { restore_linux_system_proxy(backup) }
#[cfg(not(any(windows, target_os = "linux")))]
fn restore_system_proxy(_backup: &Path) -> Result<(), String> { Ok(()) }

trait IfEmpty { fn if_empty(self, fallback: &str) -> String; }
impl IfEmpty for String { fn if_empty(self, fallback: &str) -> String { if self.trim().is_empty() { fallback.into() } else { self } } }


#[derive(serde::Serialize)]
pub struct BackupInfo {
    pub id: String,
    pub created_at: u128,
    pub size: u64,
}

fn backup_directory(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app.path().app_local_data_dir().map_err(|e| e.to_string())?.join("backups");
    fs::create_dir_all(&dir).map_err(|e| format!("Не удалось создать каталог резервных копий: {e}"))?;
    Ok(dir)
}

fn valid_backup_id(id: &str) -> bool {
    id.starts_with("backup-") && id.ends_with(".json")
        && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'.')
}

#[tauri::command]
pub fn core_backup_directory(app: AppHandle) -> Result<String, String> {
    Ok(backup_directory(&app)?.display().to_string())
}

#[tauri::command]
pub fn core_backup_list(app: AppHandle) -> Result<Vec<BackupInfo>, String> {
    let dir = backup_directory(&app)?;
    let mut backups = Vec::new();
    for entry in fs::read_dir(dir).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        let path = entry.path();
        let id = entry.file_name().to_string_lossy().to_string();
        if !valid_backup_id(&id) || !path.is_file() { continue; }
        let metadata = fs::metadata(&path).map_err(|e| e.to_string())?;
        let created_at = id.trim_start_matches("backup-").trim_end_matches(".json").parse::<u128>().unwrap_or(0);
        backups.push(BackupInfo { id, created_at, size: metadata.len() });
    }
    backups.sort_by(|a, b| b.created_at.cmp(&a.created_at));
    Ok(backups)
}

#[tauri::command]
pub fn core_backup_create(app: AppHandle, data: Value) -> Result<BackupInfo, String> {
    if !data.get("subscriptions").and_then(Value::as_array).is_some() {
        return Err("Резервная копия не содержит список профилей".into());
    }
    let dir = backup_directory(&app)?;
    let created_at = SystemTime::now().duration_since(UNIX_EPOCH).map_err(|e| e.to_string())?.as_millis();
    let id = format!("backup-{created_at}.json");
    let path = dir.join(&id);
    let bytes = serde_json::to_vec_pretty(&data).map_err(|e| e.to_string())?;
    write_private(&path, &bytes)?;
    Ok(BackupInfo { id, created_at, size: bytes.len() as u64 })
}

#[tauri::command]
pub fn core_backup_restore(app: AppHandle, id: String) -> Result<Value, String> {
    if !valid_backup_id(&id) { return Err("Некорректное имя резервной копии".into()); }
    let path = backup_directory(&app)?.join(id);
    let bytes = fs::read(path).map_err(|e| format!("Не удалось прочитать резервную копию: {e}"))?;
    serde_json::from_slice(&bytes).map_err(|e| format!("Резервная копия повреждена: {e}"))
}

#[tauri::command]
pub fn core_backup_delete(app: AppHandle, id: String) -> Result<(), String> {
    if !valid_backup_id(&id) { return Err("Некорректное имя резервной копии".into()); }
    fs::remove_file(backup_directory(&app)?.join(id)).map_err(|e| format!("Не удалось удалить резервную копию: {e}"))
}

fn open_port() -> Result<u16, String> {
    let listener = TcpListener::bind(("127.0.0.1", 0)).map_err(|e| format!("Локальный порт: {e}"))?;
    listener.local_addr().map(|addr| addr.port()).map_err(|e| e.to_string())
}


fn route_string_list(routing: &Value, key: &str) -> Vec<String> {
    let values: Vec<String> = if let Some(items) = routing.get(key).and_then(Value::as_array) {
        items.iter().filter_map(Value::as_str).map(str::to_string).collect()
    } else {
        routing.get(key).and_then(Value::as_str).unwrap_or("")
            .split(|c| c == '\n' || c == ',' || c == ';').map(str::to_string).collect()
    };
    values.into_iter().map(|item| item.trim().trim_start_matches("*.").trim_start_matches('.').to_ascii_lowercase())
        .filter(|item| !item.is_empty() && item.len() <= 253 && item.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-'))
        .take(256).collect()
}

fn route_enabled(routing: &Value, key: &str, default: bool) -> bool {
    routing.get(key).and_then(Value::as_bool).unwrap_or(default)
}

fn route_country(routing: &Value) -> Option<String> {
    let value = routing.get("geoCountry").and_then(Value::as_str)?.trim().to_ascii_lowercase();
    if value.len() == 2 && value.bytes().all(|b| b.is_ascii_lowercase()) { Some(value) } else { None }
}

fn doh_parts(routing: &Value) -> (String, u16, String) {
    let value = routing.get("dohUrl").and_then(Value::as_str).unwrap_or("https://cloudflare-dns.com/dns-query");
    let Some(rest) = value.strip_prefix("https://") else { return ("cloudflare-dns.com".into(), 443, "/dns-query".into()); };
    let (authority, path) = rest.split_once('/').unwrap_or((rest, "dns-query"));
    let (host, port) = authority.rsplit_once(':').filter(|(_, p)| p.parse::<u16>().is_ok()).unwrap_or((authority, "443"));
    if host.is_empty() || host.contains('@') || host.chars().any(char::is_control) {
        return ("cloudflare-dns.com".into(), 443, "/dns-query".into());
    }
    (host.to_string(), port.parse().unwrap_or(443), format!("/{path}"))
}


fn add_singbox_geosite(rule_sets: &mut Vec<Value>, tag: &str) -> Option<String> {
    let normalized = tag.trim().strip_prefix("geosite:").unwrap_or(tag).trim().to_ascii_lowercase();
    if normalized.is_empty() || !normalized.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') {
        return None;
    }
    let set_tag = format!("geosite-{normalized}");
    if !rule_sets.iter().any(|set| set.get("tag").and_then(Value::as_str) == Some(set_tag.as_str())) {
        rule_sets.push(json!({
            "type":"remote",
            "tag":set_tag,
            "format":"binary",
            "url":format!("https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/{set_tag}.srs"),
            "http_client":{"detour":"direct"},
            "update_interval":"24h"
        }));
    }
    Some(set_tag)
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
    let mut rules = vec![json!({"action":"sniff"}), json!({"protocol":"dns","action":"hijack-dns"})];
    let mut rule_sets: Vec<Value> = Vec::new();
    if route_enabled(routing, "rulesEnabled", true) {
        if route_enabled(routing, "blockAds", false) {
            let ads_tag = add_singbox_geosite(&mut rule_sets, "category-ads-all").unwrap();
            rules.push(json!({"rule_set":[ads_tag],"action":"reject"}));
        }
        let blocked = route_string_list(routing, "blockDomains");
        let direct = route_string_list(routing, "directDomains");
        let proxied = route_string_list(routing, "proxyDomains");
        if !blocked.is_empty() { rules.push(json!({"domain_suffix":blocked,"action":"reject"})); }
        if !direct.is_empty() { rules.push(json!({"domain_suffix":direct,"action":"route","outbound":"direct"})); }
        if !proxied.is_empty() { rules.push(json!({"domain_suffix":proxied,"action":"route","outbound":"proxy"})); }

        let blocked_sites: Vec<String> = route_string_list(routing, "blockGeoSiteTags").iter()
            .filter_map(|tag| add_singbox_geosite(&mut rule_sets, tag)).collect();
        let direct_sites: Vec<String> = route_string_list(routing, "directGeoSiteTags").iter()
            .filter_map(|tag| add_singbox_geosite(&mut rule_sets, tag)).collect();
        let proxy_sites: Vec<String> = route_string_list(routing, "proxyGeoSiteTags").iter()
            .filter_map(|tag| add_singbox_geosite(&mut rule_sets, tag)).collect();
        if !blocked_sites.is_empty() { rules.push(json!({"rule_set":blocked_sites,"action":"reject"})); }
        if !direct_sites.is_empty() { rules.push(json!({"rule_set":direct_sites,"action":"route","outbound":"direct"})); }
        if !proxy_sites.is_empty() { rules.push(json!({"rule_set":proxy_sites,"action":"route","outbound":"proxy"})); }

        if let Some(country) = route_country(routing) {
            let ip_tag = format!("geoip-{country}");
            rule_sets.push(json!({
                "type":"remote","tag":ip_tag,"format":"binary",
                "url":format!("https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/{ip_tag}.srs"),
                "http_client":{"detour":"direct"},"update_interval":"24h"
            }));
            rules.push(json!({"rule_set":[ip_tag],"action":"route","outbound":"direct"}));
        }
        if route_enabled(routing, "bypassLan", true) {
            rules.push(json!({"ip_is_private":true,"action":"route","outbound":"direct"}));
        }
    }
    let (doh_host, doh_port, doh_path) = doh_parts(routing);
    let dns_route = routing.get("dnsRoute").and_then(Value::as_str).filter(|v| *v == "direct").unwrap_or("proxy");
    let dns_strategy = routing.get("dnsStrategy").and_then(Value::as_str)
        .filter(|v| ["prefer_ipv4","prefer_ipv6","ipv4_and_ipv6"].contains(v)).unwrap_or("prefer_ipv4");
    let final_outbound = if routing.get("policy").and_then(Value::as_str) == Some("direct") { "direct" } else { "proxy" };
    Ok(json!({
        "log":{"level":"info","timestamp":true},
        "dns":{
            "servers":[
                {"type":"local","tag":"bootstrap"},
                {"type":"https","tag":"dns-remote","server":doh_host,"server_port":doh_port,"path":doh_path,"detour":dns_route,"tls":{"enabled":true,"server_name":doh_host}}
            ],
            "final":"dns-remote","strategy":dns_strategy
        },
        "inbounds":inbounds,
        "outbounds":[outbound,{"type":"direct","tag":"direct"}],
        "route":{
            "auto_detect_interface":true,
            "default_domain_resolver":{"server":"bootstrap"},
            "rule_set":rule_sets,
            "final":final_outbound,
            "rules":rules
        },
        "experimental":{"cache_file":{"enabled":true}}
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
    let mut proxy = match protocol {
        "vless" => {
            let mut user = json!({"id":outbound.get("uuid").and_then(Value::as_str).unwrap_or(""),"encryption":"none"});
            if let Some(flow) = outbound.get("flow").and_then(Value::as_str).filter(|v| !v.is_empty()) { user["flow"] = json!(flow); }
            json!({"protocol":"vless","settings":{"vnext":[{"address":server,"port":server_port,"users":[user]}]},"streamSettings":stream})
        },
        "vmess" => json!({"protocol":"vmess","settings":{"vnext":[{"address":server,"port":server_port,"users":[{"id":outbound.get("uuid").and_then(Value::as_str).unwrap_or(""),"alterId":0,"security":outbound.get("security").and_then(Value::as_str).unwrap_or("auto")}]}]},"streamSettings":stream}),
        "trojan" => json!({"protocol":"trojan","settings":{"servers":[{"address":server,"port":server_port,"password":outbound.get("password").and_then(Value::as_str).unwrap_or("")}]},"streamSettings":stream}),
        "shadowsocks" => json!({"protocol":"shadowsocks","settings":{"servers":[{"address":server,"port":server_port,"password":outbound.get("password").and_then(Value::as_str).unwrap_or(""),"method":outbound.get("method").and_then(Value::as_str).unwrap_or("aes-128-gcm"),"uot":true}]},"streamSettings":stream}),
        "hysteria2" | "tuic" | "anytls" => return Err(format!("Протокол {protocol} не поддерживается Xray-core. Выберите sing-box.")),
        other => return Err(format!("Протокол {other} не поддерживается Xray-адаптером")),
    };
    proxy["tag"] = json!("proxy");
    let mut rules: Vec<Value> = Vec::new();
    if route_enabled(routing, "rulesEnabled", true) {
        if route_enabled(routing, "blockAds", true) {
            rules.push(json!({"type":"field","domain":["geosite:category-ads-all"],"outboundTag":"block"}));
        }
        let blocked = route_string_list(routing, "blockDomains");
        let direct = route_string_list(routing, "directDomains");
        let proxied = route_string_list(routing, "proxyDomains");
        if !blocked.is_empty() { rules.push(json!({"type":"field","domain":blocked.iter().map(|d|format!("domain:{d}")).collect::<Vec<_>>(),"outboundTag":"block"})); }
        if !direct.is_empty() { rules.push(json!({"type":"field","domain":direct.iter().map(|d|format!("domain:{d}")).collect::<Vec<_>>(),"outboundTag":"direct"})); }
        if !proxied.is_empty() { rules.push(json!({"type":"field","domain":proxied.iter().map(|d|format!("domain:{d}")).collect::<Vec<_>>(),"outboundTag":"proxy"})); }
        let blocked_sites = route_string_list(routing, "blockGeoSiteTags");
        let direct_sites = route_string_list(routing, "directGeoSiteTags");
        let proxy_sites = route_string_list(routing, "proxyGeoSiteTags");
        if !blocked_sites.is_empty() { rules.push(json!({"type":"field","domain":blocked_sites.iter().map(|tag|format!("geosite:{tag}")).collect::<Vec<_>>(),"outboundTag":"block"})); }
        if !direct_sites.is_empty() { rules.push(json!({"type":"field","domain":direct_sites.iter().map(|tag|format!("geosite:{tag}")).collect::<Vec<_>>(),"outboundTag":"direct"})); }
        if !proxy_sites.is_empty() { rules.push(json!({"type":"field","domain":proxy_sites.iter().map(|tag|format!("geosite:{tag}")).collect::<Vec<_>>(),"outboundTag":"proxy"})); }
        if let Some(country) = route_country(routing) {
            rules.push(json!({"type":"field","ip":[format!("geoip:{country}")],"outboundTag":"direct"}));
        }
        if route_enabled(routing, "bypassLan", true) {
            rules.push(json!({"type":"field","ip":["geoip:private"],"outboundTag":"direct"}));
        }
    }
    let doh_base = routing.get("dohUrl").and_then(Value::as_str).filter(|v| v.starts_with("https://")).unwrap_or("https://cloudflare-dns.com/dns-query");
    let doh_url = if routing.get("dnsRoute").and_then(Value::as_str) == Some("direct") { doh_base.replacen("https://", "https+local://", 1) } else { doh_base.to_string() };
    let dns_strategy = match routing.get("dnsStrategy").and_then(Value::as_str).unwrap_or("prefer_ipv4") { "prefer_ipv6" => "UseIPv6", "ipv4_and_ipv6" => "UseIP", _ => "UseIPv4" };
    if routing.get("policy").and_then(Value::as_str) == Some("direct") {
        rules.push(json!({"type":"field","network":"tcp,udp","outboundTag":"direct"}));
    }
    let direct = json!({"protocol":"freedom","tag":"direct"});
    let block = json!({"protocol":"blackhole","tag":"block"});
    let outbounds = vec![proxy, direct, block];
    Ok(json!({
        "log":{"loglevel":"warning"},
        "dns":{"servers":[doh_url],"queryStrategy":dns_strategy},
        "inbounds":[{"tag":"wayline-socks","listen":"127.0.0.1","port":port,"protocol":"socks","settings":{"auth":"noauth","udp":true,"ip":"127.0.0.1"}}],
        "outbounds":outbounds,
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
        #[cfg(windows)] { use std::os::windows::process::CommandExt; command.creation_flags(0x08000000); }
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
    expire_at: Option<u64>,
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
    let expire_at = response.headers().get("subscription-userinfo").and_then(|value| value.to_str().ok()).and_then(|value| value.split(';').find_map(|part| part.trim().strip_prefix("expire=").and_then(|v| v.parse::<u64>().ok())))
        .or_else(|| response.headers().get("profile-expire").and_then(|value| value.to_str().ok()).and_then(|value| value.trim().parse::<u64>().ok()));
    let mut bytes = Vec::new();
    response.take((MAX_SIZE + 1) as u64).read_to_end(&mut bytes).map_err(|e| e.to_string())?;
    if bytes.len() > MAX_SIZE { return Err("Подписка превышает 2 МБ".into()); }
    let content = String::from_utf8(bytes).map_err(|_| "Ответ подписки не UTF-8".to_string())?;
    Ok(SubscriptionFetch { content, title, expire_at })
}


#[tauri::command]
pub fn core_ping(host: String, port: u16) -> Result<u64, String> {
    if host.trim().is_empty() || host.len() > 253 || host.chars().any(char::is_control) || port == 0 {
        return Err("Некорректный адрес сервера".into());
    }
    let addresses = (host.as_str(), port).to_socket_addrs().map_err(|e| format!("DNS: {e}"))?;
    let started = Instant::now();
    for address in addresses.take(8) {
        if TcpStream::connect_timeout(&address, Duration::from_secs(2)).is_ok() {
            return Ok(started.elapsed().as_millis() as u64);
        }
    }
    Err("Сервер не ответил на TCP-проверку".into())
}

#[tauri::command]
pub fn core_status(app: AppHandle, state: State<'_, CoreState>) -> Result<CoreStatus, String> {
    let mut managed = state.0.lock().map_err(|e| e.to_string())?;
    managed.refresh();
    if managed.child.is_none() {
        if let Ok(data_dir) = app.path().app_local_data_dir() {
            let stale_backup = data_dir.join("proxy-previous.json");
            if stale_backup.is_file() { let _ = restore_system_proxy(&stale_backup); }
        }
    }
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
    if mode == "tun" { request_windows_tun_elevation(&app, &outbound, &mode, &engine, &routing)?; }
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
        #[cfg(windows)] { use std::os::windows::process::CommandExt; check.creation_flags(0x08000000); }
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
    #[cfg(target_os = "linux")]
    let mut command = if mode == "tun" && unsafe { libc::geteuid() } != 0 {
        let mut elevated = Command::new("pkexec"); elevated.arg(&binary); elevated
    } else { Command::new(&binary) };
    #[cfg(not(target_os = "linux"))]
    let mut command = Command::new(&binary);
    #[cfg(windows)] { use std::os::windows::process::CommandExt; command.creation_flags(0x08000000); }
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
    managed.mode = Some(mode.clone());
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
    if result.is_ok() && mode == "proxy" && cfg!(any(windows, target_os = "linux")) {
        let backup = dir.join("proxy-previous.json");
        managed.proxy_backup = Some(backup.clone());
        if let Err(error) = enable_system_proxy(port, &engine, &backup) { managed.stop(); return Err(error); }
    }
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
        let cfg = create_singbox_config(json!({"type":"vless","server":"vpn.example.com","server_port":443,"uuid":"abc"}), "tun", 2080, &json!({})).unwrap();
        assert_eq!(cfg["inbounds"][1]["type"], "tun");
        assert_eq!(cfg["inbounds"][1]["auto_route"], true);
        assert_eq!(cfg["outbounds"][0]["server"], "vpn.example.com");
        assert_eq!(cfg["route"]["auto_detect_interface"], true);
        assert_eq!(cfg["outbounds"][1]["tag"], "direct");
        assert_eq!(cfg["route"]["rules"][3]["ip_is_private"], true);
    }
    #[test]
    fn xray_generates_vless_reality_socks_config() {
        let outbound = json!({"type":"vless","server":"vpn.example.com","server_port":443,"uuid":"1234","flow":"xtls-rprx-vision","tls":{"enabled":true,"server_name":"example.com","utls":{"enabled":true,"fingerprint":"chrome"},"reality":{"enabled":true,"public_key":"pub","short_id":"abcd"}}});
        let cfg = create_xray_config(outbound, 2080, &json!({})).unwrap();
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
    fn singbox_route_preferences_are_applied() {
        let routing = json!({
            "rulesEnabled": true,
            "blockAds": false,
            "bypassLan": true,
            "policy": "direct",
            "directDomains": "direct.example\nlocal.example",
            "proxyDomains": "proxy.example",
            "blockDomains": "blocked.example",
            "dohUrl": "https://dns.google/dns-query",
            "dnsRoute": "direct",
            "geoCountry": "fi",
            "directGeoSiteTags": "category-ru"
        });
        let cfg = create_singbox_config(
            json!({"type":"vless","server":"vpn.example.com","server_port":443,"uuid":"abc"}),
            "tun", 2080, &routing
        ).unwrap();
        assert_eq!(cfg["route"]["final"], "direct");
        assert_eq!(cfg["dns"]["servers"][1]["server"], "dns.google");
        assert_eq!(cfg["dns"]["servers"][1]["detour"], "direct");
        assert_eq!(cfg["route"]["rules"][2]["domain_suffix"][0], "blocked.example");
        assert!(cfg["route"]["rule_set"].as_array().unwrap().iter().any(|set| set["tag"] == "geosite-category-ru"));
        assert!(cfg["route"]["rule_set"].as_array().unwrap().iter().any(|set| set["tag"] == "geoip-fi"));
    }

    #[test]
    fn xray_route_preferences_use_geodata_and_doh() {
        let routing = json!({
            "rulesEnabled": true,
            "blockAds": false,
            "bypassLan": true,
            "policy": "direct",
            "geoCountry": "fr",
            "directDomains": "direct.example",
            "directGeoSiteTags": "category-ru",
            "dohUrl": "https://cloudflare-dns.com/dns-query",
            "dnsRoute": "direct"
        });
        let cfg = create_xray_config(
            json!({"type":"vless","server":"vpn.example.com","server_port":443,"uuid":"abc"}),
            2080, &routing
        ).unwrap();
        assert_eq!(cfg["outbounds"][0]["tag"], "proxy");
        assert_eq!(cfg["routing"]["rules"].as_array().unwrap().last().unwrap()["outboundTag"], "direct");
        assert_eq!(cfg["dns"]["servers"][0], "https+local://cloudflare-dns.com/dns-query");
        assert!(cfg["routing"]["rules"].as_array().unwrap().iter().any(|rule| rule["domain"][0] == "geosite:category-ru"));
        assert!(cfg["routing"]["rules"].as_array().unwrap().iter().any(|rule| rule["ip"][0] == "geoip:fr"));
    }

    #[test]
    fn xray_rejects_hysteria2() {
        let outbound = json!({"type":"hysteria2","server":"vpn.example.com","server_port":443,"password":"secret"});
        assert!(create_xray_config(outbound, 2080, &json!({})).is_err());
    }
    #[test]
    fn rejects_bad_servers() {
        assert!(create_singbox_config(json!({"type":"vless","server":"","server_port":443}), "tun", 2080, &json!({})).is_err());
        assert!(create_singbox_config(json!({"type":"direct","server":"vpn.example.com","server_port":443}), "tun", 2080, &json!({})).is_err());
    }
    #[test]
    fn local_mode_is_not_tun() {
        let cfg = create_singbox_config(json!({"type":"trojan","server":"vpn.example.com","server_port":443}), "proxy", 2080, &json!({})).unwrap();
        assert_eq!(cfg["inbounds"].as_array().unwrap().len(), 1);
    }
}
