import { NextResponse } from "next/server";
import OpenAI from "openai";

const AGENT_PROMPTS = {
  novelist: `Tu es un Maître Romancier. Ton expertise porte sur la structure narrative, le développement profond des personnages, les arcs narratifs et le style littéraire. Ta mission est d'aider l'utilisateur à transformer ses idées en récits captivants.`,
  lyricist: `Tu es un Parolier et Poète d'élite. Ton domaine est la musicalité des mots, les schémas de rimes complexes, la métrique et le rythme.`,
  screenwriter: `Tu es un Scénariste de Cinéma chevronné. Expert en formatage de script, économie de dialogue, mise en scène visuelle et structure en trois actes.`,
  linguist: `Tu es un Linguiste et Étymologiste. Analyse le choix des mots de l'utilisateur. Explique l'étymologie et les nuances sémantiques.`
};

export async function POST(req: Request) {
  try {
    const { message, agent, model, userApiKey } = await req.json();

    // On utilise la clé fournie par l'utilisateur, ou la clé serveur en dernier recours
    const apiKey = userApiKey || process.env.OPENROUTER_API_KEY || process.env.OPENAI_API_KEY;

    if (!apiKey) {
      return NextResponse.json({ 
        error: "Clé API manquante. Veuillez entrer votre clé OpenRouter dans les paramètres du chat." 
      }, { status: 401 });
    }

    // Initialisation dynamique par requête
    const openai = new OpenAI({
      apiKey: apiKey,
      baseURL: apiKey.startsWith("sk-or-") ? "https://openrouter.ai/api/v1" : undefined,
    });

    const systemPrompt = AGENT_PROMPTS[agent as keyof typeof AGENT_PROMPTS] || AGENT_PROMPTS.novelist;

    const response = await openai.chat.completions.create({
      model: model || "openai/gpt-3.5-turbo",
      messages: [
        { role: "system", content: systemPrompt },
        { role: "user", content: message }
      ],
      temperature: 0.7,
    });

    return NextResponse.json({ text: response.choices[0]?.message?.content || "Pas de réponse." });
  } catch (error: any) {
    return NextResponse.json({ error: "Erreur IA", details: error.message }, { status: 500 });
  }
}