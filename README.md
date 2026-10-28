# ScribUp

Outils d'écriture rap / chant avec Claude.

| Dossier | Contenu |
|---|---|
| [`android/`](android/README.md) | **App Android perso** : écriture multilingue avec Claude, mode Live synchronisé sur FL Studio (paroles, ad-libs, mesures), accords et tonalité en direct |
| [`pc-bridge/`](pc-bridge/README.md) | **Pont PC** : script MIDI FL Studio + compagnon Python qui écoute le son du PC (accords, tonalité, grille par mesure) et parle au téléphone par le Wi-Fi |
| `src/` | Application web Next.js (version web d'origine) |

## Démarrage rapide (app + FL Studio)

1. Récupère l'APK : onglet **Actions** de GitHub → dernier run vert → artefact `scribup-apk` → installe-le sur ton téléphone.
2. Dans l'app : ⚙ Réglages → colle ta clé API Claude.
3. Sur le PC : suis [`pc-bridge/README.md`](pc-bridge/README.md) (loopMIDI + script FL + `start_bridge.bat`).
4. Dans l'app : Réglages → PC trouvé (ou IP) + PIN → Connecter. Ouvre un morceau → onglet **Live** et lance la lecture dans FL.

Pour tester sans FL Studio : `python scribup_bridge.py --demo`.

---

## Application web (Next.js)

This is a [Next.js](https://nextjs.org) project bootstrapped with [`create-next-app`](https://nextjs.org/docs/app/api-reference/cli/create-next-app).

## Getting Started

First, run the development server:

```bash
npm run dev
# or
yarn dev
# or
pnpm dev
# or
bun dev
```

Open [http://localhost:3000](http://localhost:3000) with your browser to see the result.

You can start editing the page by modifying `app/page.tsx`. The page auto-updates as you edit the file.

This project uses [`next/font`](https://nextjs.org/docs/app/building-your-application/optimizing/fonts) to automatically optimize and load [Geist](https://vercel.com/font), a new font family for Vercel.

## Learn More

To learn more about Next.js, take a look at the following resources:

- [Next.js Documentation](https://nextjs.org/docs) - learn about Next.js features and API.
- [Learn Next.js](https://nextjs.org/learn) - an interactive Next.js tutorial.

You can check out [the Next.js GitHub repository](https://github.com/vercel/next.js) - your feedback and contributions are welcome!

## Deploy on Vercel

The easiest way to deploy your Next.js app is to use the [Vercel Platform](https://vercel.com/new?utm_medium=default-template&filter=next.js&utm_source=create-next-app&utm_campaign=create-next-app-readme) from the creators of Next.js.

Check out our [Next.js deployment documentation](https://nextjs.org/docs/app/building-your-application/deploying) for more details.
