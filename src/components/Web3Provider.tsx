'use client';
import { WagmiProvider, createConfig, http } from "wagmi";
import { mainnet, bsc, bscTestnet } from "wagmi/chains";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ConnectKitProvider, getDefaultConfig } from "connectkit";

const config = createConfig(
  getDefaultConfig({
    chains: [bsc, bscTestnet, mainnet],
    transports: {
      [bsc.id]: http(),
      [bscTestnet.id]: http(),
      [mainnet.id]: http(),
    },
    walletConnectProjectId: "bf5b828f9d5f7823e200676a266395b0", // Exemple
    appName: "ScribUp",
  })
);

const queryClient = new QueryClient();

export const Web3Provider = ({ children }: { children: React.ReactNode }) => {
  return (
    <WagmiProvider config={config}>
      <QueryClientProvider client={queryClient}>
        <ConnectKitProvider theme="retro">
          {children}
        </ConnectKitProvider>
      </QueryClientProvider>
    </WagmiProvider>
  );
};
