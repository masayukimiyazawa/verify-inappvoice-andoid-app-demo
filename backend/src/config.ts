import dotenv from 'dotenv';
import fs from 'fs';
import path from 'path';
dotenv.config();

function loadPrivateKey(): string {
  if (process.env.VONAGE_PRIVATE_KEY) {
    return process.env.VONAGE_PRIVATE_KEY;
  }
  const keyPath = process.env.VONAGE_PRIVATE_KEY_PATH;
  if (keyPath) {
    const resolved = path.resolve(keyPath);
    if (fs.existsSync(resolved)) {
      return fs.readFileSync(resolved, 'utf-8');
    }
  }
  return '';
}

export const config = {
  vonage: {
    apiKey: process.env.VONAGE_API_KEY || '',
    apiSecret: process.env.VONAGE_API_SECRET || '',
    applicationId: process.env.VONAGE_APPLICATION_ID || '',
    privateKey: loadPrivateKey(),
    brandName: process.env.VONAGE_BRAND_NAME || 'VoiceVerify',
    phoneNumber: process.env.VONAGE_PHONE_NUMBER || '',
    region: 'apac' as const,
  },
  mongodb: {
    uri: process.env.MONGODB_URI || (process.env.NODE_ENV === 'production'
      ? (() => { throw new Error('MONGODB_URI is required in production'); })()
      : 'mongodb://localhost:27017'),
    dbName: process.env.MONGODB_DB_NAME || 'voiceverify',
  },
  app: {
    domain: process.env.APP_DOMAIN || '',
    port: Number(process.env.PORT) || 3000,
    allowedOrigins: process.env.ALLOWED_ORIGINS
      ? process.env.ALLOWED_ORIGINS.split(',').map((s) => s.trim())
      : ['*'],
    allowedPhoneNumber: process.env.ALLOWED_PHONE_NUMBER || '',
  },
};
