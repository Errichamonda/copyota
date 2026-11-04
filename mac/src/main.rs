use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::{Engine, engine::general_purpose::STANDARD as BASE64};
use objc2::AnyThread;
use objc2::rc::autoreleasepool;
use objc2::runtime::ProtocolObject;
use objc2_app_kit::{NSImage, NSPasteboard, NSPasteboardTypeString, NSPasteboardWriting};
use objc2_foundation::{NSArray, NSData, NSString};
use qrcode::{Color, EcLevel, QrCode};
use std::collections::HashMap;
use std::error::Error;
use std::fs;
use std::io::{ErrorKind, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::os::unix::fs::OpenOptionsExt;
use std::path::PathBuf;
use std::process::{Command, exit};
use std::thread::sleep;
use std::time::{Duration, Instant};
use tungstenite::Message;

const LABEL: &str = "com.copyota";
const APK_URL: &str = "https://github.com/Errichamonda/copyota/releases/latest/download/copyota.apk";
const WORKER_JS: &str = include_str!("../../worker/index.js");
const WORKER_TOML: &str = include_str!("../../worker/wrangler.toml");
const IDLE: Duration = Duration::from_secs(20); // silence before a ping, and then before giving up

fn die(message: impl std::fmt::Display) -> ! {
    eprintln!("{message}");
    exit(1)
}

fn home() -> PathBuf {
    std::env::var_os("HOME").map(PathBuf::from).unwrap_or_else(|| die("HOME isn't set"))
}

fn config_dir() -> PathBuf {
    home().join(".config/copyota")
}

fn plist_path() -> PathBuf {
    home().join(format!("Library/LaunchAgents/{LABEL}.plist"))
}

// ~/.config/copyota/env holds ROOM, KEY and URL as KEY=value lines, written by `copyota setup`.
fn parse_config(text: &str) -> HashMap<String, String> {
    // split_once keeps the "=" that ends the base64 key
    text.lines().filter_map(|l| l.split_once('=')).map(|(k, v)| (k.into(), v.into())).collect()
}

fn config() -> HashMap<String, String> {
    parse_config(&fs::read_to_string(config_dir().join("env")).unwrap_or_default())
}

fn random<const N: usize>() -> [u8; N] {
    let mut bytes = [0; N];
    getrandom::fill(&mut bytes).unwrap_or_else(|e| die(e));
    bytes
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

// Messages are a type byte plus a body: 0 is text, 1 is one chunk of an image.
// An image chunk is an 8-byte transfer id, its index, the chunk count, then the bytes.
#[derive(Debug, PartialEq)]
enum Clip {
    Text(String),
    Image(Vec<u8>),
}

struct Transfer {
    started: Instant,
    chunks: HashMap<u8, Vec<u8>>,
}

struct Inbox {
    cipher: Aes256Gcm,
    transfers: HashMap<[u8; 8], Transfer>,
}

impl Inbox {
    fn new(key: &[u8]) -> Option<Self> {
        Some(Inbox { cipher: Aes256Gcm::new_from_slice(key).ok()?, transfers: HashMap::new() })
    }

    // A sealed message is base64 of a 12-byte nonce, the ciphertext, then the tag.
    fn open(&mut self, body: &str) -> Option<Clip> {
        let plain = BASE64.decode(body).ok().and_then(|sealed| {
            let nonce = Nonce::try_from(sealed.get(..12)?).ok()?;
            self.cipher.decrypt(&nonce, &sealed[12..]).ok()
        });
        let Some((&kind, body)) = plain.as_deref().and_then(|p| p.split_first()) else {
            println!("ignored a message that failed to decrypt");
            return None;
        };
        match kind {
            0 => String::from_utf8(body.to_vec()).ok().map(Clip::Text),
            1 => self.receive_chunk(body).map(Clip::Image),
            _ => {
                println!("ignored a message of unknown type {kind}");
                None
            }
        }
    }

    fn receive_chunk(&mut self, body: &[u8]) -> Option<Vec<u8>> {
        if body.len() <= 10 {
            return None;
        }
        let id: [u8; 8] = body[..8].try_into().ok()?;
        let (index, total) = (body[8], body[9]);
        if total == 0 || total > 32 || index >= total {
            return None;
        }

        // ponytail: a stalled transfer is only dropped when the next chunk arrives.
        self.transfers.retain(|_, t| t.started.elapsed() < Duration::from_secs(30));
        let transfer = self.transfers.entry(id).or_insert_with(|| Transfer { started: Instant::now(), chunks: HashMap::new() });
        transfer.chunks.insert(index, body[10..].to_vec());
        if transfer.chunks.len() != total as usize {
            return None;
        }
        let done = self.transfers.remove(&id)?;
        Some((0..total).flat_map(|i| done.chunks[&i].iter().copied()).collect())
    }
}

fn paste(clip: Clip) {
    autoreleasepool(|_| {
        let board = NSPasteboard::generalPasteboard();
        match clip {
            Clip::Text(text) => {
                board.clearContents();
                board.setString_forType(&NSString::from_str(&text), unsafe { NSPasteboardTypeString });
            }
            Clip::Image(bytes) => {
                let Some(image) = NSImage::initWithData(NSImage::alloc(), &NSData::with_bytes(&bytes)) else {
                    println!("ignored an image that didn't decode");
                    return;
                };
                board.clearContents();
                board.writeObjects(&NSArray::from_retained_slice(&[ProtocolObject::<dyn NSPasteboardWriting>::from_retained(image)]));
            }
        }
    });
}

// A sleeping Mac or a changed network leaves a dead socket that never errors,
// so ping it after a quiet spell and drop it if nothing comes back.
fn listen(url: &str, inbox: &mut Inbox) -> Result<(), Box<dyn Error>> {
    let uri: tungstenite::http::Uri = url.parse()?;
    let port = uri.port_u16().unwrap_or(if uri.scheme_str() == Some("wss") { 443 } else { 80 });
    let tcp = (uri.host().ok_or("no host")?, port).to_socket_addrs()?
        .find_map(|address| TcpStream::connect_timeout(&address, Duration::from_secs(10)).ok())
        .ok_or("couldn't reach the relay")?;
    tcp.set_read_timeout(Some(IDLE))?;
    tcp.set_write_timeout(Some(IDLE))?;
    let (mut socket, _) = tungstenite::client_tls(url, tcp).map_err(|e| e.to_string())?;
    println!("connected");

    let mut pinged = false;
    loop {
        match socket.read() {
            Ok(message) => {
                pinged = false;
                if let Message::Text(body) = message {
                    if let Some(clip) = inbox.open(body.as_str()) {
                        paste(clip);
                    }
                }
            }
            Err(tungstenite::Error::Io(e)) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                if pinged {
                    return Err("the relay stopped answering".into());
                }
                socket.send(Message::Ping(Default::default()))?;
                pinged = true;
            }
            Err(e) => return Err(e.into()),
        }
    }
}

fn run() -> ! {
    let config = config();
    let setting = |name: &str| config.get(name).unwrap_or_else(|| die("not set up, run copyota setup"));
    let key = BASE64.decode(setting("KEY")).unwrap_or_else(|_| die("the key in the config isn't valid base64"));
    let mut inbox = Inbox::new(&key).unwrap_or_else(|| die("the key in the config isn't 32 bytes"));
    let url = format!("{}/{}", setting("URL").replacen("http", "ws", 1), setting("ROOM"));
    loop {
        println!("connecting");
        if let Err(e) = listen(&url, &mut inbox) {
            println!("disconnected: {e}");
        }
        sleep(Duration::from_secs(3));
    }
}

// Draws a QR code with dark-on-white half blocks, which scan on dark terminals too.
fn qr_lines(text: &str) -> Vec<String> {
    let code = QrCode::with_error_correction_level(text, EcLevel::L).unwrap_or_else(|e| die(e));
    let (size, colors) = (code.width() as i32, code.to_colors());
    let dark = |x: i32, y: i32| (0..size).contains(&x) && (0..size).contains(&y) && colors[(y * size + x) as usize] == Color::Dark;
    let quiet = 2;
    (-quiet..size + quiet).step_by(2).map(|y| {
        let row: String = (-quiet..size + quiet).map(|x| match (dark(x, y), dark(x, y + 1)) {
            (true, true) => '█',
            (true, false) => '▀',
            (false, true) => '▄',
            _ => ' ',
        }).collect();
        format!("\x1b[30;47m{row}\x1b[0m")
    }).collect()
}

fn wrangler() -> Command {
    let mut command = Command::new("npx");
    command.args(["-y", "wrangler@4"]);
    command
}

// wrangler appends one JSON line per event, the deploy event lists the Worker's URLs.
fn workers_url(log: &str) -> Option<&str> {
    log.match_indices("https://").find_map(|(start, _)| {
        let rest = &log[start..];
        let end = rest[8..].find(|c: char| !c.is_ascii_alphanumeric() && c != '.' && c != '-')? + 8;
        rest[..end].ends_with(".workers.dev").then_some(&rest[..end])
    })
}

// Guides the user through what wrangler needs, then deploys the relay to their own account.
fn deploy() {
    if Command::new("npx").arg("--version").output().is_err() {
        die("Node.js is needed to deploy the relay. Install it from https://nodejs.org, then run this again.");
    }
    let whoami = wrangler().arg("whoami").output().unwrap_or_else(|e| die(e));
    if !String::from_utf8_lossy(&whoami.stdout).contains("You are logged in") {
        println!("Step 1: sign in to Cloudflare. A free account is enough, and your browser will open.");
        if !wrangler().arg("login").status().is_ok_and(|s| s.success()) {
            die("couldn't sign in to Cloudflare");
        }
    }
    println!("Step 2: deploying the relay to your Cloudflare account.");
    println!("If wrangler asks you to pick a workers.dev subdomain, any name works.");

    let worker = config_dir().join("worker");
    let log = worker.join("deploy.log");
    let written = fs::create_dir_all(&worker)
        .and_then(|_| fs::write(worker.join("index.js"), WORKER_JS))
        .and_then(|_| fs::write(worker.join("wrangler.toml"), WORKER_TOML));
    written.unwrap_or_else(|e| die(e));

    // Cloudflare sometimes says a Worker doesn't exist right after uploading it, so try a few times.
    for attempt in 1..=3 {
        let _ = fs::remove_file(&log);
        let status = wrangler().arg("deploy").current_dir(&worker).env("WRANGLER_OUTPUT_FILE_PATH", &log).status();
        if status.is_ok_and(|s| s.success()) {
            break;
        }
        if attempt == 3 {
            die("couldn't deploy the relay");
        }
        println!("Deploy failed, retrying in 10 seconds.");
        sleep(Duration::from_secs(10));
    }
    let events = fs::read_to_string(&log).unwrap_or_default();
    let url = workers_url(&events).unwrap_or_else(|| die("wrangler didn't report a workers.dev URL, so the deploy may have failed"));

    let env = config_dir().join("env");
    let old = fs::read_to_string(&env).unwrap_or_else(|e| die(e));
    let kept: String = old.lines().filter(|l| !l.starts_with("URL=")).map(|l| format!("{l}\n")).collect();
    fs::write(&env, format!("{kept}URL={url}\n")).unwrap_or_else(|e| die(e));
}

fn launchctl_target() -> String {
    let id = Command::new("id").arg("-u").output().unwrap_or_else(|e| die(e));
    format!("gui/{}", String::from_utf8_lossy(&id.stdout).trim())
}

fn stop_agent(domain: &str) {
    let _ = Command::new("launchctl").args(["bootout", &format!("{domain}/{LABEL}")]).stderr(std::process::Stdio::null()).status();
}

// Starts the Mac agent at login, from wherever this executable sits now.
fn install() {
    let exe = std::env::current_exe().unwrap_or_else(|e| die(e));
    let plist = plist_path();
    let log = "/tmp/copyota.log";
    let body = format!(r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>{LABEL}</string>
  <key>ProgramArguments</key><array><string>{}</string><string>run</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>{log}</string>
  <key>StandardErrorPath</key><string>{log}</string>
</dict></plist>
"#, exe.display());
    fs::create_dir_all(plist.parent().unwrap()).and_then(|_| fs::write(&plist, body)).unwrap_or_else(|e| die(e));
    let domain = launchctl_target();
    stop_agent(&domain);
    if !Command::new("launchctl").arg("bootstrap").arg(&domain).arg(&plist).status().is_ok_and(|s| s.success()) {
        die("launchctl couldn't start the Mac agent");
    }
}

// The QR holds a link to this user's own relay. The key sits in the #fragment, so the relay never sees it.
fn pair() {
    let config = config();
    let (Some(url), Some(room), Some(key)) = (config.get("URL"), config.get("ROOM"), config.get("KEY")) else {
        die("not set up yet, run copyota setup");
    };
    let key = key.replace('+', "%2B").replace('/', "%2F").replace('=', "%3D");
    println!("\nStep 3: pair your phone.");
    println!("  a. Install the Copyota app on it: {APK_URL}");
    println!("  b. Scan this code with the phone's camera, open the link, and tap Open Copyota.\n");
    let link = format!("{url}/pair#room={room}&key={key}");
    for line in qr_lines(&link) {
        println!("{line}");
    }
    println!("\n     Can't scan it? In the app, tap Enter pairing link and paste this:\n     {link}");
    println!("\n  c. In Settings > Accessibility > Copyota, turn the service on. If Android says the setting");
    println!("     is restricted, open Settings > Apps > Copyota, tap the ⋮ menu and choose Allow restricted settings.\n");
    println!("The code and link hold your private key, so don't share them. Run copyota pair to show them again.");
}

fn setup() {
    let env = config_dir().join("env");
    if !env.exists() {
        fs::create_dir_all(config_dir()).unwrap_or_else(|e| die(e));
        let mut file = fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(&env).unwrap_or_else(|e| die(e));
        write!(file, "ROOM={}\nKEY={}\n", hex(&random::<16>()), BASE64.encode(random::<32>())).unwrap_or_else(|e| die(e));
    }
    deploy();
    install();
    pair();
}

fn uninstall() {
    stop_agent(&launchctl_target());
    let _ = fs::remove_file(plist_path());
    println!("Mac agent stopped. Your key is still in {}.", config_dir().join("env").display());
    println!("To remove the relay too, run: cd {} && npx wrangler@4 delete", config_dir().join("worker").display());
}

fn main() {
    match std::env::args().nth(1).as_deref() {
        Some("setup") => setup(),
        Some("pair") => pair(),
        Some("uninstall") => uninstall(),
        Some("run") => run(),
        _ => die("usage: copyota setup|pair|uninstall|run"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEY: [u8; 32] = [7; 32];

    fn seal(plain: &[u8]) -> String {
        let nonce = [9u8; 12];
        let sealed = Aes256Gcm::new_from_slice(&KEY).unwrap().encrypt(&Nonce::try_from(&nonce[..]).unwrap(), plain).unwrap();
        BASE64.encode([&nonce[..], &sealed].concat())
    }

    fn chunk(id: u8, index: u8, total: u8, bytes: &[u8]) -> String {
        seal(&[&[1], &[id; 8][..], &[index, total], bytes].concat())
    }

    #[test]
    fn opens_text() {
        let mut inbox = Inbox::new(&KEY).unwrap();
        assert_eq!(inbox.open(&seal(b"\0caf\xc3\xa9")), Some(Clip::Text("café".into())));
    }

    #[test]
    fn rejects_bad_messages() {
        let mut inbox = Inbox::new(&KEY).unwrap();
        let mut tampered = BASE64.decode(seal(b"\0hello")).unwrap();
        *tampered.last_mut().unwrap() ^= 1;
        for body in [BASE64.encode(tampered), "not base64!".into(), BASE64.encode([1, 2, 3]), seal(b""), seal(b"\x02hello")] {
            assert_eq!(inbox.open(&body), None);
        }
        assert!(Inbox::new(&[0; 16]).is_none());
    }

    #[test]
    fn reassembles_out_of_order_chunks() {
        let mut inbox = Inbox::new(&KEY).unwrap();
        assert_eq!(inbox.open(&chunk(1, 2, 3, b"c")), None);
        assert_eq!(inbox.open(&chunk(2, 0, 2, b"other")), None);
        assert_eq!(inbox.open(&chunk(1, 0, 3, b"a")), None);
        assert_eq!(inbox.open(&chunk(1, 1, 3, b"b")), Some(Clip::Image(b"abc".to_vec())));
        assert_eq!(inbox.transfers.len(), 1);
    }

    #[test]
    fn rejects_bad_chunk_headers() {
        let mut inbox = Inbox::new(&KEY).unwrap();
        for body in [chunk(1, 0, 0, b"a"), chunk(1, 3, 3, b"a"), chunk(1, 0, 33, b"a"), chunk(1, 0, 1, b"")] {
            assert_eq!(inbox.open(&body), None);
        }
        assert!(inbox.transfers.is_empty());
    }

    #[test]
    fn config_keeps_base64_padding() {
        let config = parse_config("ROOM=abc\nKEY=aGk=\n\nURL=https://x.workers.dev\n");
        assert_eq!(config["KEY"], "aGk=");
        assert_eq!(config["URL"], "https://x.workers.dev");
    }

    #[test]
    fn finds_workers_url() {
        let log = r#"{"type":"deploy","targets":["https://example.com/a","https://copyota.me-1.workers.dev"]}"#;
        assert_eq!(workers_url(log), Some("https://copyota.me-1.workers.dev"));
        assert_eq!(workers_url(r#"{"url":"https://example.com"}"#), None);
    }

    #[test]
    fn qr_rows_cover_the_code_and_quiet_zone() {
        let lines = qr_lines("https://copyota.me.workers.dev/pair#room=0&key=1");
        let side = QrCode::with_error_correction_level("https://copyota.me.workers.dev/pair#room=0&key=1", EcLevel::L).unwrap().width() + 4;
        assert_eq!(lines.len(), side.div_ceil(2));
    }
}
