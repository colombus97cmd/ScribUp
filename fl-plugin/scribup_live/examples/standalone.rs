//! Lance ScribUp Live hors de FL (pour l'essayer) : cargo run -p scribup_live --example standalone --features standalone
fn main() {
    nih_plug::nih_export_standalone::<scribup_live::ScribUpLive>();
}
