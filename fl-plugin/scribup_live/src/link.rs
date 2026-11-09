//! Liaison avec le pont ScribUp (pc-bridge) sur le même PC : on s'y connecte comme « écran live »
//! (pas de PIN depuis le PC lui-même) et on garde le dernier état reçu pour la fenêtre du plugin.

use serde_json::Value;
use std::collections::BTreeMap;
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};
use tungstenite::stream::MaybeTlsStream;
use tungstenite::{Message, WebSocket};

pub const DEFAULT_URL: &str = "ws://127.0.0.1:8765/";

#[derive(Debug, Clone, Default)]
pub struct Song {
    pub title: String,
    pub bpm: i64,
    pub key: String,
    pub lyrics: String,
    pub bars_per_line: f64,
    pub chord_grid: BTreeMap<i64, String>,
}

#[derive(Debug, Clone)]
pub struct ChatMessage {
    pub from_user: bool,
    pub text: String,
}

#[derive(Debug, Clone, Default)]
pub struct FlInfo {
    pub connected: bool,
    pub playing: bool,
    pub bpm: f64,
    pub bar: i64,
    pub beat: i64,
    pub beat_frac: f64,
    pub beats_per_bar: i64,
}

#[derive(Debug, Clone, Default)]
pub struct LiveState {
    pub connected: bool,
    pub status: String,
    pub pc_name: String,
    /// Écran envoyé par le téléphone (ou la version web)
    pub has_mirror: bool,
    pub source: String,
    pub tab: String,
    pub cursor_line: Option<usize>,
    pub song: Option<Song>,
    pub chat: Vec<ChatMessage>,
    /// Réponse de Claude en train de s'écrire
    pub streaming: Option<String>,
    /// Réponse terminée, affichée jusqu'au prochain écran du téléphone
    pub answered: Option<String>,
    pub last_mirror: Option<Instant>,
    pub fl: FlInfo,
    pub fl_received: Option<Instant>,
    pub grid: BTreeMap<i64, String>,
    /// Compteur de changements : la fenêtre ne recalcule que si ça bouge
    pub version: u64,
}

impl LiveState {
    /// Position FL en mesures (1.0 = début de la mesure 1), extrapolée avec le tempo.
    pub fn position(&self) -> Option<f64> {
        let fl = &self.fl;
        if !fl.connected || fl.beats_per_bar <= 0 {
            return None;
        }
        let bpb = fl.beats_per_bar as f64;
        let mut pos = fl.bar as f64 + ((fl.beat - 1) as f64 + fl.beat_frac) / bpb;
        if fl.playing {
            if let Some(at) = self.fl_received {
                let elapsed = at.elapsed().as_secs_f64().min(0.5);
                pos += elapsed * fl.bpm / 60.0 / bpb;
            }
        }
        Some(pos)
    }

    pub fn chords(&self) -> &BTreeMap<i64, String> {
        match &self.song {
            Some(song) if self.grid.is_empty() => &song.chord_grid,
            _ => &self.grid,
        }
    }

    /// Applique un message JSON du pont. Renvoie false si le pont refuse la connexion.
    pub fn apply(&mut self, msg: &Value) -> bool {
        let kind = msg["type"].as_str().unwrap_or("");
        match kind {
            "welcome" => {
                self.connected = true;
                self.pc_name = msg["name"].as_str().unwrap_or("").to_string();
                self.status = format!("Connecté au pont ({})", self.pc_name);
            }
            "error" => {
                self.status = msg["message"]
                    .as_str()
                    .unwrap_or("Refusé par le pont")
                    .to_string();
                return false;
            }
            "state" => {
                let fl = &msg["fl"];
                self.fl = FlInfo {
                    connected: fl["connected"].as_bool().unwrap_or(false),
                    playing: fl["playing"].as_bool().unwrap_or(false),
                    bpm: fl["bpm"].as_f64().unwrap_or(120.0),
                    bar: fl["bar"].as_i64().unwrap_or(1),
                    beat: fl["beat"].as_i64().unwrap_or(1),
                    beat_frac: fl["beatFrac"].as_f64().unwrap_or(0.0),
                    beats_per_bar: fl["beatsPerBar"].as_i64().unwrap_or(4),
                };
                self.fl_received = Some(Instant::now());
                return true; // la position seule ne compte pas comme un changement de contenu
            }
            "grid" => self.grid = parse_grid(&msg["bars"]),
            "mirror" => {
                self.has_mirror = true;
                self.last_mirror = Some(Instant::now());
                self.answered = None;
                self.source = msg["source"].as_str().unwrap_or("android").to_string();
                self.tab = msg["tab"].as_str().unwrap_or("write").to_string();
                self.cursor_line = msg["cursorLine"]
                    .as_i64()
                    .filter(|l| *l >= 0)
                    .map(|l| l as usize);
                self.song = msg["song"].as_object().map(|s| Song {
                    title: s
                        .get("title")
                        .and_then(Value::as_str)
                        .unwrap_or("Sans titre")
                        .to_string(),
                    bpm: s.get("bpm").and_then(Value::as_i64).unwrap_or(0),
                    key: s
                        .get("key")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .to_string(),
                    lyrics: s
                        .get("lyrics")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .to_string(),
                    bars_per_line: s
                        .get("barsPerLine")
                        .and_then(Value::as_f64)
                        .filter(|b| *b > 0.0)
                        .unwrap_or(1.0),
                    chord_grid: s.get("chordGrid").map(parse_grid).unwrap_or_default(),
                });
                self.chat = msg["chat"]
                    .as_array()
                    .map(|items| {
                        items
                            .iter()
                            .map(|m| ChatMessage {
                                from_user: m["role"].as_str() == Some("user"),
                                text: m["text"].as_str().unwrap_or("").to_string(),
                            })
                            .collect()
                    })
                    .unwrap_or_default();
                self.streaming = msg["streaming"].as_str().map(str::to_string);
            }
            "claude_delta" => self.streaming = msg["text"].as_str().map(str::to_string),
            "claude_done" => {
                self.streaming = None;
                self.answered = msg["text"].as_str().map(str::to_string);
            }
            _ => return true,
        }
        self.version += 1;
        true
    }
}

fn parse_grid(value: &Value) -> BTreeMap<i64, String> {
    value
        .as_object()
        .map(|bars| {
            bars.iter()
                .filter_map(|(k, v)| Some((k.parse().ok()?, v.as_str()?.to_string())))
                .collect()
        })
        .unwrap_or_default()
}

/// Fil réseau : se (re)connecte au pont tant que le plugin existe.
pub struct Link {
    stop: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl Link {
    pub fn spawn(state: Arc<Mutex<LiveState>>, url: String) -> Self {
        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = stop.clone();
        let handle = std::thread::Builder::new()
            .name("scribup-live".into())
            .spawn(move || run(state, url, stop_flag))
            .ok();
        Self { stop, handle }
    }
}

impl Drop for Link {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

fn set_status(state: &Mutex<LiveState>, connected: bool, status: &str) {
    if let Ok(mut s) = state.lock() {
        if s.connected != connected || s.status != status {
            s.connected = connected;
            s.status = status.to_string();
            s.version += 1;
        }
    }
}

fn sleep_unless_stopped(stop: &AtomicBool, total: Duration) {
    let end = Instant::now() + total;
    while Instant::now() < end && !stop.load(Ordering::Relaxed) {
        std::thread::sleep(Duration::from_millis(100));
    }
}

fn connect(url: &str) -> Result<WebSocket<MaybeTlsStream<TcpStream>>, String> {
    let (mut ws, _) = tungstenite::connect(url).map_err(|e| e.to_string())?;
    if let MaybeTlsStream::Plain(stream) = ws.get_mut() {
        // Lecture bloquante limitée : le fil vérifie régulièrement s'il doit s'arrêter
        let _ = stream.set_read_timeout(Some(Duration::from_millis(300)));
        let _ = stream.set_nodelay(true);
    }
    ws.send(Message::text(r#"{"type":"hello","client":"display"}"#))
        .map_err(|e| e.to_string())?;
    Ok(ws)
}

fn run(state: Arc<Mutex<LiveState>>, url: String, stop: Arc<AtomicBool>) {
    while !stop.load(Ordering::Relaxed) {
        set_status(&state, false, "Connexion au pont ScribUp…");
        let mut ws = match connect(&url) {
            Ok(ws) => ws,
            Err(_) => {
                set_status(
                    &state,
                    false,
                    "Pont ScribUp pas lancé sur ce PC (start_bridge.bat)",
                );
                sleep_unless_stopped(&stop, Duration::from_secs(2));
                continue;
            }
        };
        loop {
            if stop.load(Ordering::Relaxed) {
                let _ = ws.close(None);
                return;
            }
            match ws.read() {
                Ok(Message::Text(text)) => {
                    let Ok(value) = serde_json::from_str::<Value>(text.as_str()) else {
                        continue;
                    };
                    let accepted = state.lock().map(|mut s| s.apply(&value)).unwrap_or(true);
                    if !accepted {
                        sleep_unless_stopped(&stop, Duration::from_secs(5));
                        break;
                    }
                }
                Ok(_) => {}
                Err(tungstenite::Error::Io(e))
                    if matches!(
                        e.kind(),
                        std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                    ) => {}
                Err(_) => break,
            }
        }
        set_status(
            &state,
            false,
            "Pont ScribUp déconnecté, nouvelle tentative…",
        );
        sleep_unless_stopped(&stop, Duration::from_secs(1));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn applies_bridge_messages() {
        let mut s = LiveState::default();
        s.apply(&json!({"type": "welcome", "name": "PC", "version": 3}));
        assert!(s.connected);
        s.apply(&json!({"type": "mirror", "tab": "live", "cursorLine": 2,
            "song": {"title": "Nuit", "bpm": 90, "lyrics": "a\nb", "chordGrid": {"1": "Am"}},
            "chat": [{"role": "user", "text": "salut"}], "streaming": null}));
        assert_eq!(s.tab, "live");
        assert_eq!(s.cursor_line, Some(2));
        assert_eq!(
            s.song
                .as_ref()
                .unwrap()
                .chord_grid
                .get(&1)
                .map(String::as_str),
            Some("Am")
        );
        assert!(s.chat[0].from_user);
        s.apply(&json!({"type": "claude_delta", "id": "x", "text": "Bon"}));
        assert_eq!(s.streaming.as_deref(), Some("Bon"));
        s.apply(&json!({"type": "claude_done", "id": "x", "text": "Bonjour"}));
        assert_eq!(s.answered.as_deref(), Some("Bonjour"));
        s.apply(
            &json!({"type": "state", "fl": {"connected": true, "playing": false, "bpm": 90.0,
            "bar": 5, "beat": 3, "beatFrac": 0.0, "beatsPerBar": 4}}),
        );
        assert_eq!(s.position(), Some(5.5));
        assert!(!s.apply(&json!({"type": "error", "message": "PIN incorrect"})));
    }
}
