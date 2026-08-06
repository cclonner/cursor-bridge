//! cursor-tunnel: TCP-over-iroh sidecar for the Cursor Bridge.
//!
//! Two subcommands:
//!   listen  -- PC side: accepts iroh connections, forwards each bi-stream to a local TCP target.
//!   connect -- Phone side: local TCP listener, forwards each TCP connection as a bi-stream
//!              over a single (lazily established, auto-reconnected) iroh connection.
//!
//! All machine-readable status output is single-line JSON on stdout.
//! Human/debug logs go to stderr.

use std::io::Write as _;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use anyhow::{anyhow, Context, Result};
use clap::{Parser, Subcommand};
use iroh::endpoint::{presets, Connection, RecvStream, SendStream};
use iroh::{Endpoint, EndpointAddr, SecretKey, TransportAddr};
use iroh_tickets::endpoint::EndpointTicket;
use serde_json::json;
use tokio::io::AsyncWriteExt;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Semaphore;
use tokio_stream::StreamExt;

const ALPN: &[u8] = b"cursor-bridge/1";

/// Ограничения от DoS-амплификации: один телефон использует 1 соединение и
/// горстку потоков, эти пределы с большим запасом покрывают легитимный трафик.
const MAX_CONNS: usize = 32;
const MAX_STREAMS_PER_CONN: usize = 64;

/// IP годится для ticket/dial: RFC1918 «домашний» LAN, не VPN/WSL/CGNAT.
fn is_usable_lan_ip(ip: std::net::IpAddr) -> bool {
    match ip {
        std::net::IpAddr::V4(v4) => {
            let o = v4.octets();
            // 192.168.0.0/16 — домашний Wi‑Fi/Ethernet
            if o[0] == 192 && o[1] == 168 {
                return true;
            }
            // 10.0.0.0/8 часто VPN (Outline/Radmin/CGNAT на телефоне) — НЕ берём
            // 172.16–31 — WSL/Docker/WARP — НЕ берём
            false
        }
        std::net::IpAddr::V6(_) => false, // Cloudflare/провайдерский v6 путает dial
    }
}

/// Чистим EndpointAddr: relay всегда; IP только usable LAN (или none для relay-only).
fn filter_endpoint_addr(addr: EndpointAddr, mode: &str) -> EndpointAddr {
    let mode = mode.trim().to_ascii_lowercase();
    let mut out = EndpointAddr::new(addr.id);
    for a in addr.addrs.iter() {
        match a {
            TransportAddr::Relay(url) => {
                out = out.with_relay_url(url.clone());
            }
            TransportAddr::Ip(sa) if mode == "all" => {
                out = out.with_ip_addr(*sa);
            }
            TransportAddr::Ip(sa) if mode == "lan" || mode == "lan_relay" => {
                if is_usable_lan_ip(sa.ip()) {
                    out = out.with_ip_addr(*sa);
                }
            }
            TransportAddr::Ip(_) => {} // relay mode: drop IPs
            other => {
                // Custom и пр. — только в all
                if mode == "all" {
                    out.addrs.insert(other.clone());
                }
            }
        }
    }
    out
}

#[derive(Parser)]
#[command(name = "cursor-tunnel", about = "TCP over iroh p2p tunnel")]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// PC side: accept iroh connections and forward streams to a TCP target.
    Listen {
        /// TCP address to forward incoming streams to, e.g. 127.0.0.1:8790
        #[arg(long)]
        target: SocketAddr,
        /// Path to the persistent ed25519 secret key file (created 0600 if missing).
        #[arg(long)]
        secret_file: PathBuf,
        /// Optional allowlist file: one authorized node id (hex) per line.
        /// When given, only listed nodes are served (missing/empty => none).
        #[arg(long)]
        allow_file: Option<PathBuf>,
        /// Explicitly serve EVERY node (no allowlist). Refused unless set when
        /// no --allow-file is given: the safe state (allowlist) is the default.
        #[arg(long)]
        allow_any: bool,
    },
    /// Phone side: listen on TCP and forward connections over iroh to a ticket's node.
    Connect {
        /// Endpoint ticket of the listen side.
        #[arg(long)]
        ticket: String,
        /// Local TCP address to listen on, e.g. 127.0.0.1:0 (port 0 = ephemeral).
        #[arg(long, default_value = "127.0.0.1:0")]
        listen: SocketAddr,
        /// Persistent ed25519 secret key file → stable node id for the allowlist.
        #[arg(long)]
        secret_file: Option<PathBuf>,
        /// skip = не ждать online; bg = online фоном (default); wait = блокировать ready.
        #[arg(long, default_value = "bg")]
        online_mode: String,
        /// Таймаут online() в секундах (bg/wait). 0 = сразу skip.
        #[arg(long, default_value_t = 30)]
        online_timeout_secs: u64,
        /// Таймаут одного dial attempt.
        #[arg(long, default_value_t = 20)]
        dial_timeout_secs: u64,
        /// Сразу прогреть connect после ready.
        #[arg(long, default_value_t = true, action = clap::ArgAction::Set)]
        warmup: bool,
        /// all = как в ticket; lan = relay+RFC1918; relay = только relay (LTE).
        #[arg(long, default_value = "lan")]
        addr_mode: String,
    },
    /// Print this endpoint's node id (creating the key file if missing) and exit.
    Id {
        /// Path to the persistent ed25519 secret key file (created 0600 if missing).
        #[arg(long)]
        secret_file: PathBuf,
    },
}

/// Print one JSON object per line on stdout and flush.
fn emit(v: serde_json::Value) {
    let mut out = std::io::stdout().lock();
    let _ = writeln!(out, "{}", v);
    let _ = out.flush();
}

fn main() -> Result<()> {
    let cli = Cli::parse();
    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?;
    match cli.cmd {
        Cmd::Listen {
            target,
            secret_file,
            allow_file,
            allow_any,
        } => rt.block_on(run_listen(target, secret_file, allow_file, allow_any)),
        Cmd::Connect {
            ticket,
            listen,
            secret_file,
            online_mode,
            online_timeout_secs,
            dial_timeout_secs,
            warmup,
            addr_mode,
        } => rt.block_on(run_connect(
            ticket,
            listen,
            secret_file,
            online_mode,
            online_timeout_secs,
            dial_timeout_secs,
            warmup,
            addr_mode,
        )),
        Cmd::Id { secret_file } => rt.block_on(run_id(secret_file)),
    }
}

/// Print this endpoint's node id, then exit. MUST report the exact id that
/// `connect` will present (endpoint.id()), so it binds the same way — the
/// public-key shortcut disagreed with endpoint.id() on Android. The iroh
/// discovery layer may log an "android context" panic on a background task;
/// it is non-fatal and the id is still emitted before we return.
async fn run_id(secret_file: PathBuf) -> Result<()> {
    let secret = load_or_create_secret(&secret_file)?;
    let endpoint = Endpoint::builder(presets::N0)
        .secret_key(secret)
        .bind()
        .await
        .map_err(|e| anyhow!("failed to bind iroh endpoint: {e}"))?;
    emit(json!({ "event": "id", "node_id": endpoint.id().to_string() }));
    Ok(())
}

/// Is this remote node id allowed by the allowlist file?
/// None       -> no allowlist configured, allow everyone.
/// Some(path) -> allow only nodes listed in the file; missing/unreadable
///               file fails CLOSED (nobody), matching enable-then-register.
fn node_allowed(allow_file: &Option<PathBuf>, remote: &str) -> bool {
    match allow_file {
        None => true,
        Some(path) => match std::fs::read_to_string(path) {
            Ok(text) => text
                .lines()
                .map(|l| l.trim())
                .any(|l| !l.is_empty() && l.eq_ignore_ascii_case(remote)),
            Err(_) => false,
        },
    }
}

// ---------------------------------------------------------------------------
// Secret key persistence
// ---------------------------------------------------------------------------

fn parse_secret_bytes(raw: &[u8], path: &Path) -> Result<SecretKey> {
    if raw.len() == 32 {
        let mut b = [0u8; 32];
        b.copy_from_slice(raw);
        return Ok(SecretKey::from_bytes(&b));
    }
    let text = String::from_utf8_lossy(raw);
    let text = text.trim();
    let decoded = data_encoding::HEXLOWER_PERMISSIVE
        .decode(text.as_bytes())
        .map_err(|e| anyhow!("secret key file {} is not raw 32 bytes nor hex: {e}", path.display()))?;
    if decoded.len() != 32 {
        return Err(anyhow!(
            "secret key file {} decodes to {} bytes, expected 32",
            path.display(),
            decoded.len()
        ));
    }
    let mut b = [0u8; 32];
    b.copy_from_slice(&decoded);
    Ok(SecretKey::from_bytes(&b))
}

#[cfg(unix)]
fn load_or_create_secret(path: &Path) -> Result<SecretKey> {
    if path.exists() {
        use std::io::Read;
        use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
        let mut f = std::fs::OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NOFOLLOW)
            .open(path)
            .with_context(|| format!("opening secret key file {} (symlink?)", path.display()))?;
        let meta = f
            .metadata()
            .with_context(|| format!("stat secret key file {}", path.display()))?;
        if meta.permissions().mode() & 0o077 != 0 {
            eprintln!(
                "warning: secret key file {} was group/other-accessible; tightening to 0600",
                path.display()
            );
            f.set_permissions(std::fs::Permissions::from_mode(0o600))
                .with_context(|| {
                    format!("tightening perms on secret key file {}", path.display())
                })?;
        }
        let mut raw = Vec::new();
        f.read_to_end(&mut raw)
            .with_context(|| format!("reading secret key file {}", path.display()))?;
        return parse_secret_bytes(&raw, path);
    }
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent)?;
        }
    }
    let key = SecretKey::generate();
    let hex = data_encoding::HEXLOWER.encode(&key.to_bytes());
    {
        use std::os::unix::fs::OpenOptionsExt;
        let mut f = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(path)
            .with_context(|| format!("creating secret key file {}", path.display()))?;
        f.write_all(hex.as_bytes())?;
        f.write_all(b"\n")?;
    }
    eprintln!("generated new secret key at {}", path.display());
    Ok(key)
}

#[cfg(windows)]
fn load_or_create_secret(path: &Path) -> Result<SecretKey> {
    use std::io::Read;
    if path.exists() {
        if path
            .symlink_metadata()
            .map(|m| m.file_type().is_symlink())
            .unwrap_or(false)
        {
            return Err(anyhow!("secret key file {} is a symlink", path.display()));
        }
        let mut f = std::fs::File::open(path)
            .with_context(|| format!("opening secret key file {}", path.display()))?;
        let mut raw = Vec::new();
        f.read_to_end(&mut raw)
            .with_context(|| format!("reading secret key file {}", path.display()))?;
        return parse_secret_bytes(&raw, path);
    }
    if let Some(parent) = path.parent() {
        if !parent.as_os_str().is_empty() {
            std::fs::create_dir_all(parent)?;
        }
    }
    let key = SecretKey::generate();
    let hex = data_encoding::HEXLOWER.encode(&key.to_bytes());
    std::fs::write(path, format!("{hex}\n"))
        .with_context(|| format!("creating secret key file {}", path.display()))?;
    eprintln!("generated new secret key at {}", path.display());
    Ok(key)
}

// ---------------------------------------------------------------------------
// Path introspection
// ---------------------------------------------------------------------------

fn transport_addr_string(addr: &TransportAddr) -> String {
    match addr {
        TransportAddr::Ip(sa) => sa.to_string(),
        TransportAddr::Relay(url) => url.to_string(),
        other => format!("{other:?}"),
    }
}

/// Classify the current path snapshot of a connection.
/// Returns (path_type, addr, rtt_ms) or None when no paths are open yet.
fn classify_paths(conn: &Connection) -> Option<(&'static str, String, f64)> {
    let paths = conn.paths();
    let mut selected: Option<(String, bool, f64)> = None;
    let mut has_ip = false;
    let mut has_relay = false;
    let mut first: Option<(String, bool, f64)> = None;
    let mut min_rtt = f64::MAX;
    for p in paths.iter() {
        let is_relay = p.is_relay();
        let addr = transport_addr_string(p.remote_addr());
        let rtt_ms = p.rtt().as_secs_f64() * 1000.0;
        if p.is_ip() {
            has_ip = true;
        }
        if is_relay {
            has_relay = true;
        }
        if rtt_ms < min_rtt {
            min_rtt = rtt_ms;
        }
        if first.is_none() {
            first = Some((addr.clone(), is_relay, rtt_ms));
        }
        if p.is_selected() {
            selected = Some((addr, is_relay, rtt_ms));
        }
    }
    if let Some((addr, is_relay, rtt_ms)) = selected {
        let ptype = if is_relay { "relay" } else { "direct" };
        return Some((ptype, addr, rtt_ms));
    }
    let (addr, is_relay, rtt_ms) = first?;
    let ptype = if has_ip && has_relay {
        "mixed"
    } else if is_relay {
        "relay"
    } else {
        "direct"
    };
    let rtt = if has_ip && has_relay { min_rtt } else { rtt_ms };
    Some((ptype, addr, rtt))
}

/// Watch a connection's paths and emit {"event":"path",...} whenever the
/// classified (type, addr) changes. Runs until the connection closes.
async fn monitor_paths(conn: Connection, id: u64) {
    let mut last: Option<(&'static str, String)> = None;
    let mut stream = conn.paths_stream();
    while let Some(_snapshot) = stream.next().await {
        if let Some((ptype, addr, rtt_ms)) = classify_paths(&conn) {
            let key = (ptype, addr.clone());
            if last.as_ref() != Some(&key) {
                last = Some(key);
                emit(json!({
                    "event": "path",
                    "id": id,
                    "path": ptype,
                    "addr": addr,
                    "rtt_ms": (rtt_ms * 100.0).round() / 100.0,
                }));
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Byte pumping
// ---------------------------------------------------------------------------

/// Pump bytes both ways between a TCP stream and an iroh bi-stream.
/// TCP EOF -> finish the send stream; stream EOF/reset -> shutdown TCP write.
async fn pump(tcp: TcpStream, mut send: SendStream, mut recv: RecvStream) {
    let _ = tcp.set_nodelay(true);
    let (mut rd, mut wr) = tcp.into_split();
    let tcp_to_iroh = async {
        let res = tokio::io::copy(&mut rd, &mut send).await;
        // TCP EOF (or error): signal end of data on the QUIC stream.
        let _ = send.finish();
        // Wait for the peer to acknowledge/stop so buffered data flushes out.
        let _ = send.stopped().await;
        res
    };
    let iroh_to_tcp = async {
        let res = tokio::io::copy(&mut recv, &mut wr).await;
        let _ = wr.shutdown().await;
        res
    };
    let _ = tokio::join!(tcp_to_iroh, iroh_to_tcp);
}

// ---------------------------------------------------------------------------
// listen (PC side)
// ---------------------------------------------------------------------------

async fn run_listen(
    target: SocketAddr,
    secret_file: PathBuf,
    allow_file: Option<PathBuf>,
    allow_any: bool,
) -> Result<()> {
    // Fail-closed по умолчанию: без allowlist слушатель пробрасывает ЛЮБОЙ
    // узел интернета на локальный порт моста, дающий сетевое доверие. Отказываем,
    // если не задан ни --allow-file, ни явный --allow-any.
    if allow_file.is_none() && !allow_any {
        return Err(anyhow!(
            "refusing to listen without --allow-file (pass --allow-any to serve every node)"
        ));
    }
    let secret = load_or_create_secret(&secret_file)?;
    let endpoint = Endpoint::builder(presets::N0)
        .secret_key(secret)
        .alpns(vec![ALPN.to_vec()])
        .bind()
        .await
        .map_err(|e| anyhow!("failed to bind iroh endpoint: {e}"))?;

    // Wait until relays/discovery consider us online so the ticket is complete.
    endpoint.online().await;
    // Ticket без Radmin/WARP/WSL — иначе телефон на LTE минутами долбит мёртвые IP.
    let raw = endpoint.addr();
    let addr = filter_endpoint_addr(raw, "lan");
    let ticket = EndpointTicket::from(addr.clone());
    emit(json!({
        "event": "ready",
        "ticket": ticket.to_string(),
        "node_id": endpoint.id().to_string(),
        "addrs": addr.addrs.iter().map(|a| a.to_string()).collect::<Vec<_>>(),
    }));
    match &allow_file {
        Some(p) => eprintln!("listening via iroh (allowlist {}), forwarding to {target}", p.display()),
        None => eprintln!("listening via iroh (no allowlist), forwarding to {target}"),
    }

    let conn_ids = Arc::new(AtomicU64::new(0));
    let conn_sem = Arc::new(Semaphore::new(MAX_CONNS));
    while let Some(incoming) = endpoint.accept().await {
        // Cap concurrent connections: drop excess before spending work on them.
        let permit = match conn_sem.clone().try_acquire_owned() {
            Ok(p) => p,
            Err(_) => {
                eprintln!("connection limit ({MAX_CONNS}) reached, dropping incoming");
                continue;
            }
        };
        let id = conn_ids.fetch_add(1, Ordering::Relaxed) + 1;
        let allow_file = allow_file.clone();
        tokio::spawn(async move {
            let _permit = permit; // released when the connection task ends
            // Тайм-аут рукопожатия: slow-loris иначе держал бы слот.
            // 5с мало для LTE→relay→ПК (телефон видит "aborted by peer during handshake").
            // 45с — запас на relay; allowlist всё равно до accept_bi.
            let conn = match tokio::time::timeout(Duration::from_secs(45), incoming).await {
                Ok(Ok(c)) => c,
                Ok(Err(e)) => {
                    eprintln!("conn {id}: handshake failed: {e}");
                    return;
                }
                Err(_) => {
                    eprintln!("conn {id}: handshake timed out (45s)");
                    return;
                }
            };
            let remote = conn.remote_id().to_string();
            // Transport-layer authorization: only allowlisted phone nodes.
            if !node_allowed(&allow_file, &remote) {
                eprintln!("conn {id}: node {remote} not in allowlist, rejecting");
                drop(conn); // dropping the connection closes it; never forwarded
                return;
            }
            emit(json!({ "event": "conn", "id": id, "remote": remote }));
            let monitor = tokio::spawn(monitor_paths(conn.clone(), id));
            let stream_sem = Arc::new(Semaphore::new(MAX_STREAMS_PER_CONN));
            // Accept bi streams (one per tunneled TCP connection) until the
            // connection dies. Never propagate per-stream errors.
            loop {
                match conn.accept_bi().await {
                    Ok((send, recv)) => {
                        // Перепроверяем allowlist на КАЖДЫЙ новый поток: если узел
                        // отозвали (мост переписал allow-file), новые потоки уже
                        // не пройдут — соединение закрывается.
                        if !node_allowed(&allow_file, &remote) {
                            eprintln!("conn {id}: node {remote} revoked, closing connection");
                            drop((send, recv));
                            break;
                        }
                        // Cap concurrent streams per connection (anti-amplification).
                        let sp = match stream_sem.clone().try_acquire_owned() {
                            Ok(p) => p,
                            Err(_) => {
                                eprintln!("conn {id}: stream limit ({MAX_STREAMS_PER_CONN}) reached, dropping stream");
                                drop((send, recv)); // resets the stream
                                continue;
                            }
                        };
                        tokio::spawn(async move {
                            let _sp = sp;
                            match TcpStream::connect(target).await {
                                Ok(tcp) => pump(tcp, send, recv).await,
                                Err(e) => {
                                    eprintln!("conn {id}: tcp connect to {target} failed: {e}");
                                    // dropping send/recv resets the stream
                                }
                            }
                        });
                    }
                    Err(e) => {
                        eprintln!("conn {id}: closed: {e}");
                        break;
                    }
                }
            }
            monitor.abort();
            emit(json!({"event": "close", "id": id}));
        });
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// connect (phone side)
// ---------------------------------------------------------------------------

struct ConnectState {
    endpoint: Endpoint,
    remote: EndpointAddr,
    dial_timeout: Duration,
    conn: tokio::sync::Mutex<Option<ConnSlot>>,
}

struct ConnSlot {
    conn: Connection,
    monitor: tokio::task::JoinHandle<()>,
}

/// Get the shared iroh connection, (re)dialing with backoff if needed.
/// Emits {"event":"error",...} on every failed attempt and never gives up.
async fn ensure_conn(state: &ConnectState) -> Connection {
    let mut guard = state.conn.lock().await;
    if let Some(slot) = guard.as_ref() {
        if slot.conn.close_reason().is_none() {
            return slot.conn.clone();
        }
        slot.monitor.abort();
        *guard = None;
    }
    let mut delay = Duration::from_secs(1);
    loop {
        eprintln!("dialing {} ...", state.remote.id);
        let attempt = tokio::time::timeout(
            state.dial_timeout,
            state.endpoint.connect(state.remote.clone(), ALPN),
        )
        .await;
        match attempt {
            Ok(Ok(conn)) => {
                eprintln!("iroh connection established to {}", state.remote.id);
                let monitor = tokio::spawn(monitor_paths(conn.clone(), 0));
                *guard = Some(ConnSlot {
                    conn: conn.clone(),
                    monitor,
                });
                return conn;
            }
            Ok(Err(e)) => {
                emit(json!({"event": "error", "message": format!("iroh connect failed: {e}")}));
            }
            Err(_) => {
                emit(json!({
                    "event": "error",
                    "message": format!(
                        "iroh connect timed out after {}s",
                        state.dial_timeout.as_secs()
                    )
                }));
            }
        }
        tokio::time::sleep(delay).await;
        delay = (delay * 2).min(Duration::from_secs(15));
    }
}

/// Invalidate the shared connection if it is still the given one.
async fn invalidate_conn(state: &ConnectState, stale: &Connection) {
    let mut guard = state.conn.lock().await;
    if let Some(slot) = guard.as_ref() {
        if slot.conn.stable_id() == stale.stable_id() {
            slot.monitor.abort();
            *guard = None;
        }
    }
}

async fn run_connect(
    ticket: String,
    listen: SocketAddr,
    secret_file: Option<PathBuf>,
    online_mode: String,
    online_timeout_secs: u64,
    dial_timeout_secs: u64,
    warmup: bool,
    addr_mode: String,
) -> Result<()> {
    let ticket: EndpointTicket = ticket
        .trim()
        .parse()
        .map_err(|e| anyhow!("invalid ticket: {e}"))?;
    let remote_raw: EndpointAddr = ticket.into();
    let remote = filter_endpoint_addr(remote_raw, &addr_mode);
    if remote.addrs.is_empty() {
        return Err(anyhow!(
            "no usable addrs after addr_mode={addr_mode} (ticket без relay/LAN?)"
        ));
    }
    let mode = online_mode.trim().to_ascii_lowercase();
    let dial_timeout = Duration::from_secs(dial_timeout_secs.max(1));
    let online_timeout = Duration::from_secs(online_timeout_secs);

    let builder = Endpoint::builder(presets::N0).alpns(vec![ALPN.to_vec()]);
    let builder = match &secret_file {
        Some(path) => builder.secret_key(load_or_create_secret(path)?),
        None => builder,
    };
    let endpoint = builder
        .bind()
        .await
        .map_err(|e| anyhow!("failed to bind iroh endpoint: {e}"))?;

    emit(json!({
        "event": "cfg",
        "online_mode": mode,
        "online_timeout_secs": online_timeout_secs,
        "dial_timeout_secs": dial_timeout_secs,
        "warmup": warmup,
        "addr_mode": addr_mode.trim().to_ascii_lowercase(),
        "dial_addrs": remote.addrs.iter().map(|a| a.to_string()).collect::<Vec<_>>(),
    }));

    let mut online_at_ready = false;
    let addr_mode_l = addr_mode.trim().to_ascii_lowercase();
    // LTE/relay-only: dial ДО home-relay на телефоне часто таймаутится.
    // На ПК relay-only ок за ~2с; на LTE нужно сначала online().
    let wait_online_first = addr_mode_l == "relay" || mode == "wait";

    if wait_online_first && online_timeout_secs > 0 {
        match tokio::time::timeout(online_timeout, endpoint.online()).await {
            Ok(()) => {
                online_at_ready = true;
                emit(json!({"event": "status", "online": true}));
            }
            Err(_) => emit(json!({
                "event": "warn",
                "soft": true,
                "message": format!(
                    "online() timeout {online_timeout_secs}s перед dial (addr_mode={addr_mode_l})"
                )
            })),
        }
    } else if mode == "bg" && online_timeout_secs > 0 {
        let ep = endpoint.clone();
        let secs = online_timeout_secs;
        tokio::spawn(async move {
            match tokio::time::timeout(Duration::from_secs(secs), ep.online()).await {
                Ok(()) => emit(json!({"event": "status", "online": true})),
                Err(_) => emit(json!({
                    "event": "warn",
                    "soft": true,
                    "message": format!(
                        "phone home-relay ещё нет (online {secs}с) — dial по ticket всё равно"
                    )
                })),
            }
        });
    }

    let listener = TcpListener::bind(listen)
        .await
        .with_context(|| format!("binding tcp listener on {listen}"))?;
    let port = listener.local_addr()?.port();
    emit(json!({"event": "ready", "port": port, "online": online_at_ready}));
    eprintln!(
        "tcp listening on port {port}, tunneling to {} (mode={mode}, addr={addr_mode_l}, dial={}s)",
        remote.id,
        dial_timeout.as_secs()
    );

    let state = Arc::new(ConnectState {
        endpoint,
        remote,
        dial_timeout,
        conn: tokio::sync::Mutex::new(None),
    });

    if warmup {
        let state = state.clone();
        tokio::spawn(async move {
            emit(json!({"event": "dialing", "remote": state.remote.id.to_string()}));
            let _ = ensure_conn(&state).await;
            emit(json!({"event": "connected", "remote": state.remote.id.to_string()}));
        });
    }

    loop {
        let (tcp, peer) = match listener.accept().await {
            Ok(x) => x,
            Err(e) => {
                eprintln!("tcp accept error: {e}");
                tokio::time::sleep(Duration::from_millis(200)).await;
                continue;
            }
        };
        eprintln!("tcp client connected from {peer}");
        let state = state.clone();
        tokio::spawn(async move {
            for _attempt in 0..5 {
                let conn = ensure_conn(&state).await;
                match conn.open_bi().await {
                    Ok((send, recv)) => {
                        pump(tcp, send, recv).await;
                        return;
                    }
                    Err(e) => {
                        emit(json!({
                            "event": "error",
                            "message": format!("open_bi failed, reconnecting: {e}"),
                        }));
                        invalidate_conn(&state, &conn).await;
                    }
                }
            }
            eprintln!("giving up on tcp client {peer} after repeated open_bi failures");
        });
    }
}
