import { MongoClient, Db } from 'mongodb';
import { config } from './config.js';

let client: MongoClient;
let db: Db;
let connectionPromise: Promise<Db> | null = null;

export async function connectMongo(): Promise<Db> {
  if (db) return db;

  if (connectionPromise) return connectionPromise;

  connectionPromise = (async () => {
    client = new MongoClient(config.mongodb.uri, {
      maxPoolSize: 1,
      minPoolSize: 0,
      serverSelectionTimeoutMS: 5000,
      connectTimeoutMS: 5000,
      socketTimeoutMS: 30000,
      retryWrites: true,
    });
    await client.connect();
    db = client.db(config.mongodb.dbName);
    console.log(`MongoDB connected: ${config.mongodb.dbName}`);
    return db;
  })();

  return connectionPromise;
}

export function getDb(): Db {
  if (!db) {
    throw new Error('MongoDB not connected. Call connectMongo() first.');
  }
  return db;
}

export async function closeMongo(): Promise<void> {
  if (client) {
    await client.close();
    db = undefined as unknown as Db;
  }
}
