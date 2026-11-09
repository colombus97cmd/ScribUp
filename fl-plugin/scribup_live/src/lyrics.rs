//! Syntaxe des paroles ScribUp, identique à l'app (android/.../lyrics/LyricsParser.kt) :
//! `[Section]`, `[Section @9]`, `@17 ligne`, `ligne ~2`, `(ad-lib)`, `// commentaire`.

#[derive(Debug, Clone, PartialEq)]
pub struct Segment {
    pub text: String,
    pub adlib: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Line {
    pub index: usize,
    pub section: Option<String>,
    pub segments: Vec<Segment>,
    /// Position de départ en mesures (1.0 = premier temps de la mesure 1).
    pub start: f64,
    pub bars: f64,
    pub adlibs: Vec<String>,
    /// Numéro de la ligne dans le texte source (0-based).
    pub source_line: usize,
}

impl Line {
    pub fn bar(&self) -> i64 {
        self.start.floor() as i64
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Section {
    pub name: String,
    pub start: f64,
    pub first_line: usize,
}

#[derive(Debug, Clone, Default)]
pub struct Parsed {
    pub lines: Vec<Line>,
    pub sections: Vec<Section>,
}

impl Parsed {
    /// Index de la ligne en cours à la position [pos] (en mesures), ou None avant la première.
    pub fn line_at(&self, pos: f64) -> Option<usize> {
        let mut current = None;
        for line in &self.lines {
            if line.start <= pos + 1e-6 {
                current = Some(line.index);
            } else {
                break;
            }
        }
        current
    }
}

fn parse_bars(s: &str) -> Option<f64> {
    s.trim().replace(',', ".").parse().ok()
}

/// `[Nom]` ou `[Nom @9]` -> (nom, début éventuel).
pub fn parse_section(raw: &str) -> Option<(String, Option<f64>)> {
    let inner = raw.strip_prefix('[')?.strip_suffix(']')?;
    if inner.trim().is_empty() {
        return None;
    }
    if let Some(at) = inner.rfind('@') {
        if let Some(bars) = parse_bars(&inner[at + 1..]) {
            let name = inner[..at].trim();
            if !name.is_empty() {
                return Some((name.to_string(), Some(bars)));
            }
        }
    }
    Some((inner.trim().to_string(), None))
}

/// `@17 ligne` -> (17, "ligne").
pub fn strip_bar_prefix(raw: &str) -> Option<(f64, &str)> {
    let rest = raw.strip_prefix('@')?.trim_start();
    let end = rest.find(char::is_whitespace)?;
    let bars = parse_bars(&rest[..end])?;
    Some((bars, rest[end..].trim_start()))
}

/// `ligne ~2` -> ("ligne", 2).
pub fn strip_duration_suffix(raw: &str) -> Option<(&str, f64)> {
    let tilde = raw.rfind('~')?;
    let before = &raw[..tilde];
    if !before.ends_with(char::is_whitespace) {
        return None;
    }
    let bars = parse_bars(&raw[tilde + 1..])?;
    Some((before.trim_end(), bars))
}

/// Découpe "texte (adlib) texte" en segments ; tolère une parenthèse non fermée.
pub fn split_segments(text: &str) -> Vec<Segment> {
    let mut out = Vec::new();
    let mut buf = String::new();
    let mut depth = 0usize;
    for c in text.chars() {
        if c == '(' {
            if depth == 0 && !buf.is_empty() {
                out.push(Segment {
                    text: std::mem::take(&mut buf),
                    adlib: false,
                });
            }
            depth += 1;
            buf.push(c);
        } else if c == ')' && depth > 0 {
            buf.push(c);
            depth -= 1;
            if depth == 0 {
                out.push(Segment {
                    text: std::mem::take(&mut buf),
                    adlib: true,
                });
            }
        } else {
            buf.push(c);
        }
    }
    if !buf.is_empty() {
        out.push(Segment {
            text: buf,
            adlib: depth > 0,
        });
    }
    out
}

pub fn parse(text: &str, bars_per_line: f64) -> Parsed {
    let mut parsed = Parsed::default();
    let mut cursor = 1.0;
    let mut section: Option<String> = None;
    for (source_line, raw_line) in text.lines().enumerate() {
        let mut raw = raw_line.trim();
        if raw.is_empty() || raw.starts_with("//") {
            continue;
        }
        if let Some((name, start)) = parse_section(raw) {
            if let Some(start) = start {
                cursor = start;
            }
            section = Some(name.clone());
            parsed.sections.push(Section {
                name,
                start: cursor,
                first_line: parsed.lines.len(),
            });
            continue;
        }
        let mut explicit_start = None;
        if let Some((bars, rest)) = strip_bar_prefix(raw) {
            explicit_start = Some(bars);
            raw = rest;
        }
        let mut explicit_bars = None;
        if let Some((rest, bars)) = strip_duration_suffix(raw) {
            explicit_bars = Some(bars);
            raw = rest;
        }
        let raw = raw.trim();
        if raw.is_empty() {
            continue;
        }
        let segments = split_segments(raw);
        let only_adlib = segments.iter().all(|s| s.adlib || s.text.trim().is_empty());
        if only_adlib && explicit_bars.is_none() && explicit_start.is_none() {
            if let Some(previous) = parsed.lines.last_mut() {
                previous.adlibs.push(raw.to_string());
                continue;
            }
        }
        let start = explicit_start.unwrap_or(cursor);
        let bars = explicit_bars.unwrap_or(bars_per_line);
        parsed.lines.push(Line {
            index: parsed.lines.len(),
            section: section.clone(),
            segments,
            start,
            bars,
            adlibs: Vec::new(),
            source_line,
        });
        cursor = start + bars;
    }
    parsed
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_like_the_app() {
        let p = parse("[Intro]\n(yeah)\n\n[Couplet 1 @5]\nJe marche (skrrt)\n(ad-lib seul)\nLes lumières ~2\n@12 Mon cœur\n// note\n[Refrain]\nOn monte", 1.0);
        assert_eq!(p.sections.len(), 3);
        assert_eq!(p.sections[1].name, "Couplet 1");
        assert_eq!(p.sections[1].start, 5.0);
        let starts: Vec<f64> = p.lines.iter().map(|l| l.start).collect();
        assert_eq!(starts, vec![1.0, 5.0, 6.0, 12.0, 13.0]);
        assert_eq!(p.lines[1].adlibs, vec!["(ad-lib seul)".to_string()]);
        assert_eq!(p.lines[2].bars, 2.0);
        assert!(p.lines[1].segments[1].adlib);
        assert_eq!(p.lines[4].section.as_deref(), Some("Refrain"));
        assert_eq!(p.line_at(0.5), None);
        assert_eq!(p.line_at(6.9), Some(2));
    }
}
