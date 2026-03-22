import type { Metadata } from "next";
import { Space_Grotesk } from "next/font/google";
import "./globals.css";
import { Web3Provider } from "@/components/Web3Provider";

const spaceGrotesk = Space_Grotesk({ subsets: ["latin"] });

export const metadata: Metadata = {
  title: "ScribUp | Assistant d'Écriture Web3",
  description: "Briefer par IA pour romanciers, paroliers et scénaristes.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="fr" className="dark">
      <body className={`${spaceGrotesk.className} bg-[#020202] text-white selection:bg-[#00f2ff] selection:text-black`}>
        <Web3Provider>
          {children}
        </Web3Provider>
      </body>
    </html>
  );
}
