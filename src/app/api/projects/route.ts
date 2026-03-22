import { NextResponse } from "next/server";
import prisma from "@/lib/prisma";

export async function POST(req: Request) {
  try {
    const { userId, title, content, agent, cid } = await req.json();

    if (!userId || !content) {
      return NextResponse.json({ error: "Champs manquants" }, { status: 400 });
    }

    const project = await prisma.project.create({
      data: {
        userId,
        title: title || "Sans titre",
        content,
        agent,
        cid,
      },
    });

    return NextResponse.json(project);
  } catch (error) {
    console.error("Erreur creation projet:", error);
    return NextResponse.json({ error: "Erreur serveur" }, { status: 500 });
  }
}

export async function GET(req: Request) {
  try {
    const { searchParams } = new URL(req.url);
    const userId = searchParams.get("userId");

    if (!userId) {
      return NextResponse.json({ error: "userId manquant" }, { status: 400 });
    }

    const projects = await prisma.project.findMany({
      where: { userId },
      orderBy: { updatedAt: "desc" },
    });

    return NextResponse.json(projects);
  } catch (error) {
    console.error("Erreur fetch projets:", error);
    return NextResponse.json({ error: "Erreur serveur" }, { status: 500 });
  }
}