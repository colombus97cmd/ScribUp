import { PinataSDK } from "pinata-web3";

const pinata = new PinataSDK({
  pinataJwt: process.env.NEXT_PUBLIC_PINATA_JWT || "",
  pinataGateway: "gateway.pinata.cloud",
});

export const uploadTextToIPFS = async (text: string, title: string) => {
  try {
    const blob = new Blob([text], { type: "text/plain" });
    const file = new File([blob], `${title}.txt`, { type: "text/plain" });
    const upload = await pinata.upload.file(file);
    return upload.cid;
  } catch (error) {
    console.error("Error uploading to IPFS:", error);
    throw error;
  }
};
