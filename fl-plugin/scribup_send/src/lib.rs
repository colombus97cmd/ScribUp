//! ScribUp Send : plugin VST3 à placer sur le Master de FL Studio.
//!
//! Il laisse passer le son sans le modifier et en envoie une copie au pont ScribUp
//! (`pc-bridge/scribup_bridge.py`) sur le PC, en UDP local. Ça marche quel que soit le pilote
//! audio de FL (ASIO de la carte son comprise), là où la capture du son de Windows ne voit rien.
//!
//! Le fil audio ne fait que copier les échantillons dans un tampon circulaire sans allocation ;
//! l'envoi réseau se fait dans un fil séparé.

use nih_plug::prelude::*;
use std::net::UdpSocket;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::Duration;

/// Port UDP local où le pont écoute le plugin.
pub const BRIDGE_PORT: u16 = 8766;
pub const MAGIC: &[u8; 4] = b"SCRB";
/// Frames stéréo par paquet (~5 ms à 48 kHz).
pub const FRAMES_PER_PACKET: usize = 256;

/// Paquet : en-tête de 16 octets little-endian (« SCRB », fréquence u32, canaux u16, frames u16,
/// numéro u32) suivi des échantillons float32 stéréo entrelacés.
pub fn encode_packet(out: &mut Vec<u8>, sample_rate: u32, seq: u32, interleaved: &[f32]) {
    out.clear();
    out.extend_from_slice(MAGIC);
    out.extend_from_slice(&sample_rate.to_le_bytes());
    out.extend_from_slice(&2u16.to_le_bytes());
    out.extend_from_slice(&((interleaved.len() / 2) as u16).to_le_bytes());
    out.extend_from_slice(&seq.to_le_bytes());
    for sample in interleaved {
        out.extend_from_slice(&sample.to_le_bytes());
    }
}

/// Fil d'envoi : vide le tampon circulaire et envoie des paquets au pont.
struct Sender {
    stop: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl Sender {
    fn spawn(mut consumer: rtrb::Consumer<f32>, sample_rate: u32) -> Self {
        let stop = Arc::new(AtomicBool::new(false));
        let stop_flag = stop.clone();
        let handle = std::thread::Builder::new()
            .name("scribup-send".into())
            .spawn(move || {
                let Ok(socket) = UdpSocket::bind(("127.0.0.1", 0)) else {
                    return;
                };
                let mut samples = vec![0.0f32; FRAMES_PER_PACKET * 2];
                let mut packet = Vec::with_capacity(16 + samples.len() * 4);
                let mut seq = 0u32;
                while !stop_flag.load(Ordering::Relaxed) {
                    let Ok(chunk) = consumer.read_chunk(samples.len()) else {
                        std::thread::sleep(Duration::from_millis(1));
                        continue;
                    };
                    let (first, second) = chunk.as_slices();
                    samples[..first.len()].copy_from_slice(first);
                    samples[first.len()..].copy_from_slice(second);
                    chunk.commit_all();
                    encode_packet(&mut packet, sample_rate, seq, &samples);
                    seq = seq.wrapping_add(1);
                    // Pont pas lancé : le paquet est perdu, ce n'est pas grave.
                    let _ = socket.send_to(&packet, ("127.0.0.1", BRIDGE_PORT));
                }
            })
            .ok();
        Self { stop, handle }
    }
}

impl Drop for Sender {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

#[derive(Params)]
struct SendParams {
    #[id = "send"]
    pub send: BoolParam,
}

impl Default for SendParams {
    fn default() -> Self {
        Self {
            send: BoolParam::new("Envoyer vers ScribUp", true),
        }
    }
}

#[derive(Default)]
pub struct ScribUpSend {
    params: Arc<SendParams>,
    producer: Option<rtrb::Producer<f32>>,
    sender: Option<Sender>,
}

impl Plugin for ScribUpSend {
    const NAME: &'static str = "ScribUp Send";
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
        buffer_config: &BufferConfig,
        _context: &mut impl InitContext<Self>,
    ) -> bool {
        self.sender = None; // arrête l'éventuel fil précédent
        let sample_rate = buffer_config.sample_rate.round() as u32;
        // ~1 s de son stéréo : large marge si le fil d'envoi prend du retard
        let (producer, consumer) = rtrb::RingBuffer::new(sample_rate as usize * 2);
        self.producer = Some(producer);
        self.sender = Some(Sender::spawn(consumer, sample_rate));
        true
    }

    fn process(
        &mut self,
        buffer: &mut Buffer,
        _aux: &mut AuxiliaryBuffers,
        _context: &mut impl ProcessContext<Self>,
    ) -> ProcessStatus {
        if !self.params.send.value() {
            return ProcessStatus::Normal;
        }
        let Some(producer) = self.producer.as_mut() else {
            return ProcessStatus::Normal;
        };
        let channels = buffer.as_slice_immutable();
        let (left, right) = match channels {
            [left, right, ..] => (left, right),
            [mono] => (mono, mono),
            _ => return ProcessStatus::Normal,
        };
        // Tampon plein (fil d'envoi bloqué) : on saute ce bloc plutôt que de couper le son de FL
        if producer.slots() < left.len() * 2 {
            return ProcessStatus::Normal;
        }
        for (l, r) in left.iter().zip(right.iter()) {
            let _ = producer.push(*l);
            let _ = producer.push(*r);
        }
        ProcessStatus::Normal
    }

    fn deactivate(&mut self) {
        self.sender = None;
        self.producer = None;
    }
}

impl ClapPlugin for ScribUpSend {
    const CLAP_ID: &'static str = "com.scribup.send";
    const CLAP_DESCRIPTION: Option<&'static str> = Some("Envoie le son de FL Studio au pont ScribUp");
    const CLAP_MANUAL_URL: Option<&'static str> = Some(Self::URL);
    const CLAP_SUPPORT_URL: Option<&'static str> = None;
    const CLAP_FEATURES: &'static [ClapFeature] =
        &[ClapFeature::AudioEffect, ClapFeature::Stereo, ClapFeature::Utility];
}

impl Vst3Plugin for ScribUpSend {
    const VST3_CLASS_ID: [u8; 16] = *b"ScribUpSendFLv01";
    const VST3_SUBCATEGORIES: &'static [Vst3SubCategory] = &[Vst3SubCategory::Fx, Vst3SubCategory::Tools];
}

nih_export_clap!(ScribUpSend);
nih_export_vst3!(ScribUpSend);

#[cfg(test)]
mod tests {
    use super::*;

    /// Même paquet de référence que pc-bridge/tests/test_plugin_source.py
    #[test]
    fn packet_layout_matches_the_bridge() {
        let mut packet = Vec::new();
        encode_packet(&mut packet, 44_100, 7, &[0.5, -0.25]);
        let hex: String = packet.iter().map(|b| format!("{b:02x}")).collect();
        assert_eq!(hex, "5343524244ac0000020001000700000000000003f000080be");
    }
}
