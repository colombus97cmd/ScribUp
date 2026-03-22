import { NextResponse } from "next/server";
import OpenAI from "openai";

const openai = new OpenAI({
  apiKey: process.env.OPENROUTER_API_KEY || process.env.OPENAI_API_KEY,
  baseURL: process.env.OPENROUTER_API_KEY ? "https://openrouter.ai/api/v1" : undefined,
});

const AGENT_PROMPTS = {
  novelist: `Tu es un Maître Romancier. Ton expertise porte sur la structure narrative, le développement profond des personnages, les arcs narratifs et le style littéraire. Ta mission est d'aider l'utilisateur à transformer ses idées en récits captivants. Analyse son texte, souligne les points forts et suggère des améliorations stylistiques ou structurelles.`,
  lyricist: `Tu es un Parolier et Poète d'élite. Ton domaine est la musicalité des mots, les schémas de rimes complexes, la métrique et le rythme. Tu transmets des émotions brutes à travers le texte musical. Conseille sur les refrains percutants et les métaphores poétiques.`,
  screenwriter: `Tu es un Scénariste de Cinéma chevronné. Expert en formatage de script, économie de dialogue, mise en scène visuelle et structure en trois actes. Aide à rendre les scènes dynamiques et les dialogues naturels.`,
  linguist: `Tu es un Linguiste et Étymologiste. Analyse le choix des mots de l'utilisateur. Explique l'étymologie, le contexte culturel et les nuances sémantiques pour donner de la profondeur au texte.`
};

export async function POST(req: Request) {
  try {
    const { message, agent, model = "openai/gpt-3.5-turbo" } = await req.json();

    if (!message) {
      return NextResponse.json({ error: "Message manquant" }, { status: 400 });
    }

    const systemPrompt = AGENT_PROMPTS[agent as keyof typeof AGENT_PROMPTS] || AGENT_PROMPTS.novelist;

    const response = await openai.chat.completions.create({
      model: model,
      messages: [
        { role: "system", content: systemPrompt },
        { role: "user", content: message }
      ],
      temperature: 0.7,
      max_tokens: 1000,
    });

    const aiResult = response.choices[0]?.message?.content || "Désolé, je ne parviens pas à réfléchir correctement en ce moment.";

    return NextResponse.json({ text: aiResult });
  } catch (error: any) {
    console.error("Erreur IA:", error);
    return NextResponse.json({ 
      error: "Erreur de connexion à l'IA", 
      details: error.message 
    }, { status: 500 });
  }
}