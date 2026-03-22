import { NextResponse } from 'next/server';

const AGENT_PROMPTS = {
  novelist: "Tu es un Maître Romancier. Ton expertise porte sur la structure narrative (le voyage du héros), le développement profond des personnages, les arcs narratifs et le style littéraire. Tu aides l'utilisateur à enrichir son récit, à éviter les clichés et à maintenir une tension dramatique constante.",
  lyricist: "Tu es un Parolier et Poète d'élite. Ton domaine est la musicalité des mots, les schémas de rimes complexes, la métrique, le rythme et la transmission d'émotions brutes à travers le texte musical. Tu conseilles sur les refrains percutants et les métaphores poétiques.",
  screenwriter: "Tu es un Scénariste de Cinéma chevronné. Tu es expert en formatage de script, en économie de dialogue, en mise en scène visuelle à travers l'écrit et en structure en trois actes. Tu aides à rendre les scènes dynamiques et les dialogues naturels.",
  linguist: "Tu es un Linguiste et Étymologiste passionné. Ta mission est d'analyser le choix des mots de l'utilisateur. Tu expliques l'origine (étymologie), l'évolution historique, le contexte culturel et les nuances sémantiques des termes utilisés pour donner une profondeur intellectuelle au texte."
};

export async function POST(req: Request) {
  try {
    const { message, agent, context } = await req.json();

    // Simulation de réponse de l'IA (À remplacer par votre clé API OpenAI/Anthropic)
    // Pour le prototype, nous simulons une réponse intelligente basée sur l'agent
    const systemPrompt = AGENT_PROMPTS[agent as keyof typeof AGENT_PROMPTS] || AGENT_PROMPTS.novelist;
    
    // Simuler un délai de réflexion de l'IA
    await new Promise(resolve => setTimeout(resolve, 1000));

    let aiResponse = "";
    if (agent === 'linguist') {
      aiResponse = `Analyse Linguistique : Le terme que vous utilisez a une racine latine intéressante. Dans votre contexte, il renforce l'aspect "sacré" de la scène.`;
    } else {
      aiResponse = `En tant que ${agent === 'novelist' ? 'votre Romancier' : agent === 'lyricist' ? 'votre Parolier' : 'votre Scénariste'}, je vous suggère de renforcer l'émotion ici en utilisant des images plus sensorielles. Votre message : "${message}" est une excellente base.`;
    }

    return NextResponse.json({ text: aiResponse });
  } catch (error) {
    return NextResponse.json({ error: "Erreur de transmission" }, { status: 500 });
  }
}
