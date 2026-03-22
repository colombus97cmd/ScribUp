import { PrismaClient } from "@prisma/client";

const prismaClientSingleton = () => {
  // En Prisma 7, on passe la DATABASE_URL au constructeur via datasourceUrl
  // On utilise un cast simple pour éviter les erreurs de types TS si le client local est différent
  return new PrismaClient({
    datasourceUrl: process.env.DATABASE_URL,
  } as any);
};

declare global {
  var prisma: undefined | ReturnType<typeof prismaClientSingleton>;
}

const prisma = globalThis.prisma ?? prismaClientSingleton();

export default prisma;

if (process.env.NODE_ENV !== "production") globalThis.prisma = prisma;