import crypto from 'crypto';
import { config } from '../config.js';
import { VCREvent, VRResponse } from '../types/vcr.js';

export function verifyJWT(event: VCREvent): { userId?: string; error?: VRResponse } {
  const authHeader = event.headers['authorization'] || event.headers['Authorization'];

  if (!authHeader) {
    return { error: { status: 401, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ error: 'Missing Authorization header' }) } };
  }

  const parts = authHeader.split(' ');
  if (parts.length !== 2 || parts[0] !== 'Bearer') {
    return { error: { status: 401, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ error: 'Authorization must be Bearer <token>' }) } };
  }

  const token = parts[1];
  const segments = token.split('.');
  if (segments.length !== 3) {
    return { error: { status: 401, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ error: 'Invalid token format' }) } };
  }

  try {
    const privateKey = config.vonage.privateKey;
    if (!privateKey) {
      return { error: { status: 500, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ error: 'JWT verification not configured' }) } };
    }

    const signingInput = `${segments[0]}.${segments[1]}`;
    const signature = segments[2];

    const verify = crypto.createVerify('RSA-SHA256');
    verify.update(signingInput);
    const isValid = verify.verify(privateKey, signature, 'base64url');

    if (!isValid) {
      return { error: { status: 401, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ error: 'Invalid token signature' }) } };
    }

    const payload = JSON.parse(Buffer.from(segments[1], 'base64url').toString('utf8'));

    const now = Math.floor(Date.now() / 1000);
    if (payload.exp && payload.exp < now) {
      return { error: { status: 401, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ error: 'Token expired' }) } };
    }

    const userId = payload.user?.id;
    if (!userId) {
      return { error: { status: 401, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ error: 'Token missing user id' }) } };
    }

    return { userId };
  } catch {
    return { error: { status: 401, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ error: 'Invalid token' }) } };
  }
}
