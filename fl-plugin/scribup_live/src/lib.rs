//! ScribUp Live : plugin VST3 pour FL Studio qui affiche, dans sa fenêtre, ce que tu fais sur le
//! téléphone, en direct : le texte que tu écris (ligne du curseur surlignée), le mode Live qui
//! suit la lecture de FL, la conversation avec Claude et la grille d'accords.
//!
//! Il ne touche pas au son (il le laisse passer tel quel) : il se connecte au pont ScribUp
//! (`pc-bridge`) qui tourne sur le même PC, comme l'écran live du navigateur.

pub mod link;
pub mod lyrics;

use link::{Link, LiveState};
use nih_plug::prelude::*;
use nih_plug_egui::egui::{
    self, text::LayoutJob, Align, Color32, CornerRadius, FontId, Frame, Margin, RichText,
    ScrollArea, Sense, TextFormat,
};
use nih_plug_egui::resizable_window::ResizableWindow;
use nih_plug_egui::{create_egui_editor, EguiState};
use std::sync::{Arc, Mutex};
use std::time::Duration;

// Couleurs de l'app (android/.../ui/theme/Theme.kt)
const BG: Color32 = Color32::from_rgb(0x0e, 0x0e, 0x12);
const SURFACE1: Color32 = Color32::from_rgb(0x17, 0x17, 0x1e);
const SURFACE2: Color32 = Color32::from_rgb(0x22, 0x22, 0x2c);
const TEXT: Color32 = Color32::from_rgb(0xed, 0xed, 0xf2);
const SOFT: Color32 = Color32::from_rgb(0xb9, 0xb9, 0xc6);
const DIM: Color32 = Color32::from_rgb(0x8a, 0x8a, 0x99);
const ACCENT: Color32 = Color32::from_rgb(0xff, 0x4d, 0x8d);
const ACCENT2: Color32 = Color32::from_rgb(0x7c, 0x5c, 0xff);
const GOOD: Color32 = Color32::from_rgb(0x3d, 0xdc, 0x97);
const WARN: Color32 = Color32::from_rgb(0xff, 0xb5, 0x47);
const BAD: Color32 = Color32::from_rgb(0xff, 0x5c, 0x5c);

#[derive(Clone, Copy, PartialEq, Eq)]
enum View {
    Write,
    Live,
    Claude,
    Chords,
}

impl View {
    const ALL: [(View, &'static str); 4] = [
        (View::Write, "Écrire"),
        (View::Live, "Live"),
        (View::Claude, "Claude"),
        (View::Chords, "Accords"),
    ];

    fn from_tab(tab: &str) -> Option<View> {
        match tab {
            "write" => Some(View::Write),
            "live" => Some(View::Live),
            "claude" => Some(View::Claude),
            "chords" => Some(View::Chords),
            _ => None,
        }
    }
}

/// État de la fenêtre (choix d'affichage).
struct Ui {
    view: View,
    follow_phone: bool,
    /// Ce qui a été centré la dernière fois : on ne re-défile que si ça change
    last_focus: Option<(View, usize)>,
    parsed_for: u64,
    parsed: lyrics::Parsed,
}

#[derive(Params)]
struct LiveParams {
    /// Taille et position de la fenêtre, gardées avec le projet FL
    #[persist = "editor-state"]
    editor_state: Arc<EguiState>,
    /// Taille du texte, gardée avec le projet FL
    #[persist = "text-size"]
    text_size: Arc<Mutex<f32>>,
}

impl Default for LiveParams {
    fn default() -> Self {
        Self {
            editor_state: EguiState::from_size(900, 620),
            text_size: Arc::new(Mutex::new(30.0)),
        }
    }
}

pub struct ScribUpLive {
    params: Arc<LiveParams>,
    state: Arc<Mutex<LiveState>>,
    link: Option<Link>,
}

impl Default for ScribUpLive {
    fn default() -> Self {
        Self {
            params: Arc::new(LiveParams::default()),
            state: Arc::new(Mutex::new(LiveState::default())),
            link: None,
        }
    }
}

impl ScribUpLive {
    fn ensure_link(&mut self) {
        if self.link.is_none() {
            let url = std::env::var("SCRIBUP_BRIDGE_URL")
                .unwrap_or_else(|_| link::DEFAULT_URL.to_string());
            self.link = Some(Link::spawn(self.state.clone(), url));
        }
    }
}

impl Plugin for ScribUpLive {
    const NAME: &'static str = "ScribUp Live";
    const VENDOR: &'static str = "ScribUp";
    const URL: &'static str = "https://github.com/colombus97cmd/ScribUp";
    const EMAIL: &'static str = "";
    const VERSION: &'static str = env!("CARGO_PKG_VERSION");

    const AUDIO_IO_LAYOUTS: &'static [AudioIOLayout] = &[
        AudioIOLayout {
            main_input_channels: NonZeroU32::new(2),
            main_output_channels: NonZeroU32::new(2),
            ..AudioIOLayout::const_default()
        },
        AudioIOLayout {
            main_input_channels: NonZeroU32::new(1),
            main_output_channels: NonZeroU32::new(1),
            ..AudioIOLayout::const_default()
        },
    ];
    const MIDI_INPUT: MidiConfig = MidiConfig::None;
    const SAMPLE_ACCURATE_AUTOMATION: bool = false;

    type SysExMessage = ();
    type BackgroundTask = ();

    fn params(&self) -> Arc<dyn Params> {
        self.params.clone()
    }

    fn initialize(
        &mut self,
        _audio_io_layout: &AudioIOLayout,
        _buffer_config: &BufferConfig,
        _context: &mut impl InitContext<Self>,
    ) -> bool {
        self.ensure_link();
        true
    }

    fn process(
        &mut self,
        _buffer: &mut Buffer,
        _aux: &mut AuxiliaryBuffers,
        _context: &mut impl ProcessContext<Self>,
    ) -> ProcessStatus {
        // Le son passe sans modification
        ProcessStatus::Normal
    }

    fn editor(&mut self, _async_executor: AsyncExecutor<Self>) -> Option<Box<dyn Editor>> {
        self.ensure_link();
        let state = self.state.clone();
        let egui_state = self.params.editor_state.clone();
        let text_size = self.params.text_size.clone();
        create_egui_editor(
            self.params.editor_state.clone(),
            Ui {
                view: View::Write,
                follow_phone: true,
                last_focus: None,
                parsed_for: u64::MAX,
                parsed: Default::default(),
            },
            |ctx, _| {
                let mut visuals = egui::Visuals::dark();
                visuals.panel_fill = BG;
                visuals.window_fill = BG;
                visuals.extreme_bg_color = SURFACE1;
                ctx.set_visuals(visuals);
            },
            move |ctx, _setter, ui_state| {
                // Le fil réseau met l'état à jour : on redessine régulièrement
                ctx.request_repaint_after(Duration::from_millis(50));
                let live = match state.lock() {
                    Ok(s) => s.clone(),
                    Err(_) => return,
                };
                let mut size = text_size.lock().map(|s| *s).unwrap_or(30.0);
                ResizableWindow::new("scribup-live")
                    .min_size(egui::vec2(360.0, 240.0))
                    .show(ctx, &egui_state, |ui| {
                        draw(ui, &live, ui_state, &mut size);
                    });
                if let Ok(mut s) = text_size.lock() {
                    *s = size;
                }
            },
        )
    }
}

fn draw(ui: &mut egui::Ui, live: &LiveState, st: &mut Ui, size: &mut f32) {
    let phone_view = View::from_tab(&live.tab);
    let view = if st.follow_phone {
        phone_view.unwrap_or(st.view)
    } else {
        st.view
    };

    // --- En-tête : titre, position FL, accord, état --------------------------------------
    Frame::new()
        .fill(SURFACE1)
        .inner_margin(Margin::symmetric(14, 10))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal_wrapped(|ui| {
                let title = live
                    .song
                    .as_ref()
                    .map(|s| s.title.as_str())
                    .unwrap_or("ScribUp Live");
                ui.label(RichText::new(title).size(20.0).strong().color(TEXT));
                if let Some(song) = &live.song {
                    let meta: Vec<String> = [
                        (song.bpm > 0).then(|| format!("{} BPM", song.bpm)),
                        (!song.key.is_empty()).then(|| song.key.clone()),
                    ]
                    .into_iter()
                    .flatten()
                    .collect();
                    if !meta.is_empty() {
                        ui.label(RichText::new(meta.join(" · ")).color(SOFT));
                    }
                }
                if let Some(pos) = live.position() {
                    let bar = pos.floor();
                    let bpb = live.fl.beats_per_bar.max(1);
                    let beat = (((pos - bar) * bpb as f64).floor() as i64 + 1).clamp(1, bpb);
                    let icon = if live.fl.playing { "▶" } else { "■" };
                    ui.label(
                        RichText::new(format!("{icon} {}.{}", bar as i64, beat))
                            .size(18.0)
                            .strong()
                            .color(TEXT),
                    );
                    if let Some(chord) = live.chords().get(&(bar as i64)) {
                        ui.label(RichText::new(chord).size(20.0).strong().color(ACCENT2));
                    }
                }
                let phone_live = live
                    .last_mirror
                    .map(|t| t.elapsed().as_secs() < 15)
                    .unwrap_or(false);
                let who = if live.source == "web" {
                    "Web"
                } else {
                    "Téléphone"
                };
                let (txt, col) = match (live.connected, phone_live) {
                    (false, _) => (live.status.clone(), BAD),
                    (true, true) => (format!("{who} : en direct"), GOOD),
                    (true, false) => (format!("{} · en attente du téléphone", live.status), DIM),
                };
                ui.label(RichText::new(txt).size(12.0).color(col));
            });
        });

    // --- Onglets ------------------------------------------------------------------------
    ui.add_space(6.0);
    Frame::new()
        .inner_margin(Margin::symmetric(12, 0))
        .show(ui, |ui| {
            ui.horizontal_wrapped(|ui| {
                for (v, label) in View::ALL {
                    let selected = v == view;
                    let text = RichText::new(label).size(15.0).color(if selected {
                        Color32::WHITE
                    } else {
                        SOFT
                    });
                    let button = egui::Button::new(text)
                        .fill(if selected { ACCENT } else { SURFACE2 })
                        .corner_radius(8.0);
                    if ui.add(button).clicked() {
                        st.view = v;
                        st.follow_phone = false;
                    }
                }
                ui.add_space(12.0);
                ui.checkbox(
                    &mut st.follow_phone,
                    RichText::new("Suivre le téléphone").color(DIM),
                );
                if ui.button("A−").clicked() {
                    *size = (*size - 3.0).max(14.0);
                }
                if ui.button("A+").clicked() {
                    *size = (*size + 3.0).min(80.0);
                }
            });
        });
    ui.add_space(6.0);

    // --- Contenu ------------------------------------------------------------------------
    if st.parsed_for != live.version {
        st.parsed = live
            .song
            .as_ref()
            .map(|s| lyrics::parse(&s.lyrics, s.bars_per_line))
            .unwrap_or_default();
        st.parsed_for = live.version;
    }
    ScrollArea::vertical()
        .auto_shrink([false, false])
        .show(ui, |ui| {
            Frame::new()
                .inner_margin(Margin {
                    left: 16,
                    right: 20,
                    top: 8,
                    bottom: 0,
                })
                .show(ui, |ui| {
                    ui.set_width(ui.available_width());
                    match view {
                        View::Write => draw_write(ui, live, st, *size),
                        View::Live => draw_live(ui, live, st, *size),
                        View::Claude => draw_claude(ui, live, st, *size),
                        View::Chords => draw_chords(ui, live, *size),
                    }
                });
            ui.add_space(ui.available_height().max(200.0) * 0.5);
        });
}

fn empty(ui: &mut egui::Ui, text: &str) {
    ui.add_space(60.0);
    ui.vertical_centered(|ui| ui.label(RichText::new(text).size(18.0).color(DIM)));
}

fn fmt(size: f32, color: Color32, mono: bool) -> TextFormat {
    let font_id = if mono {
        FontId::monospace(size)
    } else {
        FontId::proportional(size)
    };
    TextFormat {
        font_id,
        color,
        ..Default::default()
    }
}

fn append_segments(
    job: &mut LayoutJob,
    segments: &[lyrics::Segment],
    size: f32,
    color: Color32,
    mono: bool,
) {
    for seg in segments {
        job.append(
            &seg.text,
            0.0,
            fmt(size, if seg.adlib { ACCENT } else { color }, mono),
        );
    }
}

/// Une ligne du texte source, colorée comme dans l'éditeur de l'app.
fn source_line_job(line: &str, size: f32) -> LayoutJob {
    let mut job = LayoutJob::default();
    let t = line.trim();
    if t.is_empty() {
        job.append(" ", 0.0, fmt(size, TEXT, true));
    } else if t.starts_with("//") {
        job.append(line, 0.0, fmt(size, DIM, true));
    } else if lyrics::parse_section(t).is_some() {
        job.append(line, 0.0, fmt(size, ACCENT2, true));
    } else {
        let mut rest = line;
        if let Some((_, after)) = lyrics::strip_bar_prefix(rest.trim_start()) {
            let cut = rest.len() - after.len();
            job.append(&rest[..cut], 0.0, fmt(size, WARN, true));
            rest = after;
        }
        let mut suffix = "";
        if let Some((before, _)) = lyrics::strip_duration_suffix(rest) {
            suffix = &rest[before.len()..];
            rest = before;
        }
        append_segments(&mut job, &lyrics::split_segments(rest), size, TEXT, true);
        if !suffix.is_empty() {
            job.append(suffix, 0.0, fmt(size, WARN, true));
        }
    }
    job.wrap.max_width = f32::INFINITY;
    job
}

fn highlighted(ui: &mut egui::Ui, on: bool, add: impl FnOnce(&mut egui::Ui)) -> egui::Response {
    let frame = Frame::new()
        .fill(if on { SURFACE2 } else { Color32::TRANSPARENT })
        .corner_radius(CornerRadius::same(8))
        .inner_margin(Margin::symmetric(10, 2));
    let response = frame
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            add(ui)
        })
        .response;
    if on {
        let r = response.rect;
        ui.painter().rect_filled(
            egui::Rect::from_min_size(r.min, egui::vec2(4.0, r.height())),
            2.0,
            ACCENT,
        );
    }
    response
}

fn scroll_once(st: &mut Ui, view: View, index: usize, response: &egui::Response) {
    if st.last_focus != Some((view, index)) {
        response.scroll_to_me(Some(Align::Center));
        st.last_focus = Some((view, index));
    }
}

fn draw_write(ui: &mut egui::Ui, live: &LiveState, st: &mut Ui, size: f32) {
    let Some(song) = &live.song else {
        return empty(ui, "En attente du téléphone…\nOuvre un morceau dans ScribUp : il s'affichera ici en direct.");
    };
    for (i, line) in song.lyrics.split('\n').enumerate() {
        let on = live.cursor_line == Some(i);
        let job = source_line_job(line, size);
        let response = highlighted(ui, on, |ui| {
            ui.add(egui::Label::new(job).wrap());
        });
        if on {
            scroll_once(st, View::Write, i, &response);
        }
    }
}

fn draw_live(ui: &mut egui::Ui, live: &LiveState, st: &mut Ui, size: f32) {
    if live.song.is_none() {
        return empty(ui, "En attente du téléphone…");
    }
    if st.parsed.lines.is_empty() {
        return empty(ui, "Pas encore de paroles.");
    }
    let pos = live.position();
    let current = match pos {
        Some(p) => st.parsed.line_at(p),
        // FL arrêté ou absent : on suit la ligne en cours d'écriture
        None => live.cursor_line.and_then(|c| {
            st.parsed
                .lines
                .iter()
                .filter(|l| l.source_line <= c)
                .map(|l| l.index)
                .last()
        }),
    };
    let chords = live.chords().clone();
    let lines = st.parsed.lines.clone();
    for line in &lines {
        if let Some(sec) = st
            .parsed
            .sections
            .iter()
            .find(|s| s.first_line == line.index)
        {
            ui.add_space(size * 0.4);
            ui.label(
                RichText::new(sec.name.to_uppercase())
                    .size(size * 0.45)
                    .strong()
                    .color(ACCENT2),
            );
        }
        let on = current == Some(line.index);
        let past = current.map(|c| line.index < c).unwrap_or(false);
        let color = if on {
            TEXT
        } else if past {
            DIM.gamma_multiply(0.6)
        } else {
            SOFT.gamma_multiply(0.7)
        };
        let response = highlighted(ui, on, |ui| {
            ui.horizontal(|ui| {
                ui.add_sized(
                    [size * 1.4, size],
                    egui::Label::new(
                        RichText::new(format!("m.{}", line.bar()))
                            .size(size * 0.4)
                            .color(DIM),
                    ),
                );
                let chord = chords.get(&line.bar()).cloned().unwrap_or_default();
                ui.add_sized(
                    [size * 1.6, size],
                    egui::Label::new(
                        RichText::new(chord)
                            .size(size * 0.45)
                            .strong()
                            .color(ACCENT2),
                    ),
                );
                ui.vertical(|ui| {
                    let mut job = LayoutJob::default();
                    append_segments(&mut job, &line.segments, size, color, false);
                    ui.add(egui::Label::new(job).wrap());
                    for adlib in &line.adlibs {
                        ui.label(RichText::new(adlib).size(size * 0.6).color(ACCENT));
                    }
                    if let (true, Some(p)) = (on, pos) {
                        let progress = ((p - line.start) / line.bars).clamp(0.0, 1.0) as f32;
                        let (rect, _) = ui.allocate_exact_size(
                            egui::vec2(ui.available_width(), 4.0),
                            Sense::hover(),
                        );
                        let filled = egui::Rect::from_min_size(
                            rect.min,
                            egui::vec2(rect.width() * progress, 4.0),
                        );
                        ui.painter().rect_filled(filled, 2.0, ACCENT);
                    }
                });
            });
        });
        if on {
            scroll_once(st, View::Live, line.index, &response);
        }
    }
}

fn draw_claude(ui: &mut egui::Ui, live: &LiveState, st: &mut Ui, size: f32) {
    let mut chat: Vec<(bool, String)> = live
        .chat
        .iter()
        .map(|m| (m.from_user, m.text.clone()))
        .collect();
    if let Some(answer) = &live.answered {
        if chat.last().map(|(_, t)| t != answer).unwrap_or(true) {
            chat.push((false, answer.clone()));
        }
    }
    let typing = live.streaming.clone();
    if chat.is_empty() && typing.is_none() {
        return empty(
            ui,
            "Pas encore de conversation avec Claude pour ce morceau.",
        );
    }
    let text_size = size * 0.6;
    for (from_user, text) in &chat {
        bubble(ui, *from_user, text, text_size);
    }
    if let Some(t) = typing {
        let shown = if t.is_empty() {
            "…".to_string()
        } else {
            format!("{t} …")
        };
        let response = bubble(ui, false, &shown, text_size);
        response.scroll_to_me(Some(Align::Max));
    } else if st.last_focus != Some((View::Claude, chat.len())) {
        ui.scroll_to_cursor(Some(Align::Max));
        st.last_focus = Some((View::Claude, chat.len()));
    }
}

fn bubble(ui: &mut egui::Ui, from_user: bool, text: &str, size: f32) -> egui::Response {
    let layout = if from_user {
        egui::Layout::right_to_left(Align::Min)
    } else {
        egui::Layout::left_to_right(Align::Min)
    };
    let response = ui
        .with_layout(layout, |ui| {
            Frame::new()
                .fill(if from_user { ACCENT2 } else { SURFACE1 })
                .corner_radius(CornerRadius::same(14))
                .inner_margin(Margin::symmetric(14, 10))
                .show(ui, |ui| {
                    ui.set_max_width(ui.available_width() * 0.85);
                    ui.add(
                        egui::Label::new(RichText::new(text).size(size).color(if from_user {
                            Color32::WHITE
                        } else {
                            TEXT
                        }))
                        .wrap(),
                    );
                })
                .response
        })
        .inner;
    ui.add_space(8.0);
    response
}

fn draw_chords(ui: &mut egui::Ui, live: &LiveState, size: f32) {
    let chords = live.chords();
    if chords.is_empty() {
        return empty(
            ui,
            "Pas encore de grille d'accords (lance la lecture dans FL Studio).",
        );
    }
    let current = live.position().map(|p| p.floor() as i64);
    let cell = (size * 3.0).max(80.0);
    let gap = ui.spacing().item_spacing.x;
    let columns = ((ui.available_width() + gap) / (cell + 20.0 + gap))
        .floor()
        .max(1.0) as usize;
    let cells: Vec<_> = chords.iter().collect();
    for row in cells.chunks(columns) {
        ui.horizontal(|ui| {
            for (bar, chord) in row {
                let (bar, chord) = (*bar, *chord);
                let on = current == Some(*bar);
                Frame::new()
                    .fill(SURFACE1)
                    .stroke(if on {
                        egui::Stroke::new(2.0_f32, ACCENT)
                    } else {
                        egui::Stroke::NONE
                    })
                    .corner_radius(CornerRadius::same(10))
                    .inner_margin(Margin::same(10))
                    .show(ui, |ui| {
                        ui.set_width(cell);
                        ui.vertical(|ui| {
                            ui.label(RichText::new(format!("mesure {bar}")).size(12.0).color(DIM));
                            ui.label(
                                RichText::new(chord)
                                    .size(size * 0.8)
                                    .strong()
                                    .color(ACCENT2),
                            );
                        });
                    });
            }
        });
        ui.add_space(4.0);
    }
}

impl ClapPlugin for ScribUpLive {
    const CLAP_ID: &'static str = "com.scribup.live";
    const CLAP_DESCRIPTION: Option<&'static str> =
        Some("Écran live ScribUp : ce que tu écris sur le téléphone, en direct");
    const CLAP_MANUAL_URL: Option<&'static str> = Some(Self::URL);
    const CLAP_SUPPORT_URL: Option<&'static str> = None;
    const CLAP_FEATURES: &'static [ClapFeature] = &[
        ClapFeature::AudioEffect,
        ClapFeature::Stereo,
        ClapFeature::Utility,
    ];
}

impl Vst3Plugin for ScribUpLive {
    const VST3_CLASS_ID: [u8; 16] = *b"ScribUpLiveFLv01";
    const VST3_SUBCATEGORIES: &'static [Vst3SubCategory] =
        &[Vst3SubCategory::Fx, Vst3SubCategory::Tools];
}

nih_export_clap!(ScribUpLive);
nih_export_vst3!(ScribUpLive);
