"use client";
// Connexion directe du navigateur au pont ScribUp du PC (pc-bridge) :
// - Claude répond via Claude Code installé sur le PC (abonnement, pas de clé API) ;
// - le texte en cours d'écriture s'affiche en direct sur l'écran live du PC (http://localhost:8765).
// Le serveur Vercel ne peut pas joindre ton PC : c'est le navigateur qui parle au pont.
import { useCallback, useEffect, useRef, useState } from "react";

export type PcStatus = "off" | "connecting" | "connected" | "error";

export type PcSettings = { host: string; port: number; pin: string };

type Pending = { onText: (text: string) => void; resolve: (text: string) => void; reject: (err: Error) => void };

const STORAGE_KEY = "scribup_pc_bridge";

export function loadPcSettings(): PcSettings {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}");
    return { host: saved.host || "localhost", port: Number(saved.port) || 8765, pin: saved.pin || "" };
  } catch {
    return { host: "localhost", port: 8765, pin: "" };
  }
}

export function savePcSettings(s: PcSettings) {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(s)); } catch {}
}

export function usePcBridge(enabled: boolean, settings: PcSettings) {
  const [status, setStatus] = useState<PcStatus>("off");
  const [message, setMessage] = useState("");
  const [claudeOnPc, setClaudeOnPc] = useState(false);
  const wsRef = useRef<WebSocket | null>(null);
  const pending = useRef(new Map<string, Pending>());

  useEffect(() => {
    if (!enabled) return;
    let closed = false;
    let retry: ReturnType<typeof setTimeout> | undefined;

    const failAll = (why: string) => {
      pending.current.forEach((p) => p.reject(new Error(why)));
      pending.current.clear();
    };

    const open = () => {
      setStatus("connecting");
      setMessage(`Connexion à ${settings.host}:${settings.port}…`);
      let ws: WebSocket;
      try {
        ws = new WebSocket(`ws://${settings.host}:${settings.port}/`);
      } catch (e) {
        setStatus("error");
        setMessage(`Adresse invalide (${(e as Error).message})`);
        return;
      }
      wsRef.current = ws;
      ws.onopen = () => ws.send(JSON.stringify({ type: "hello", pin: settings.pin, client: "web" }));
      ws.onmessage = (ev) => {
        if (typeof ev.data !== "string") return;
        const msg = JSON.parse(ev.data);
        if (msg.type === "welcome") {
          setStatus("connected");
          setMessage(`Connecté à ${msg.name}`);
          setClaudeOnPc(Boolean(msg.claude));
        } else if (msg.type === "error") {
          closed = true; // PIN faux : inutile de réessayer en boucle
          setStatus("error");
          setMessage(msg.message || "Erreur");
        } else if (msg.type === "claude_delta") {
          pending.current.get(msg.id)?.onText(msg.text);
        } else if (msg.type === "claude_done") {
          const p = pending.current.get(msg.id);
          pending.current.delete(msg.id);
          p?.resolve(msg.text);
        } else if (msg.type === "claude_error") {
          const p = pending.current.get(msg.id);
          pending.current.delete(msg.id);
          p?.reject(new Error(msg.message));
        }
      };
      ws.onclose = () => {
        wsRef.current = null;
        failAll("Connexion au PC perdue");
        if (closed) return;
        setStatus("error");
        setMessage("PC injoignable : le pont est-il lancé ? Nouvelle tentative…");
        retry = setTimeout(open, 3000);
      };
    };
    open();
    return () => {
      closed = true;
      clearTimeout(retry);
      wsRef.current?.close();
      wsRef.current = null;
      failAll("Déconnecté du PC");
    };
  }, [enabled, settings.host, settings.port, settings.pin]);

  const send = useCallback((obj: object) => {
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) return false;
    ws.send(JSON.stringify(obj));
    return true;
  }, []);

  /** Fait répondre Claude Code sur le PC ; [onText] reçoit le texte complet au fil de l'eau. */
  const askClaude = useCallback(
    (system: string, messages: { role: "user" | "assistant"; content: string }[], onText: (t: string) => void, model?: string) =>
      new Promise<string>((resolve, reject) => {
        const id = Math.random().toString(36).slice(2);
        pending.current.set(id, { onText, resolve, reject });
        if (!send({ type: "claude", id, system, model, messages })) {
          pending.current.delete(id);
          reject(new Error("Le PC n'est pas connecté."));
        }
      }),
    [send],
  );

  /** Recopie l'écran sur l'écran live du PC. */
  const sendMirror = useCallback((mirror: object) => send({ type: "mirror", source: "web", ...mirror }), [send]);

  return { status: enabled ? status : ("off" as PcStatus), message: enabled ? message : "", claudeOnPc, askClaude, sendMirror };
}
