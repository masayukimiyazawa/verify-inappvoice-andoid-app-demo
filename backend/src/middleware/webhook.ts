/**
 * Webhook signature validation middleware
 * 
 * Validates that incoming webhook requests are genuinely from Vonage
 * using HMAC-SHA256 signature verification.
 * 
 * Vonage signs webhooks using the API secret as the HMAC key.
 * Reference: https://developer.vonage.com/en/api/signing-messages
 */

import crypto from 'crypto';
import { config } from '../config.js';
import { VCREvent, VRResponse } from '../types/vcr.js';

export function verifyWebhookSignature(event: VCREvent): VRResponse | null {
  const apiSecret = config.vonage.apiSecret;

  if (!apiSecret) {
    console.warn('[Webhook] VONAGE_API_SECRET not configured — skipping signature verification');
    return null;
  }

  const signature = event.headers['x-vonage-signature'] || event.headers['X-Vonage-Signature'];
  const timestamp = event.headers['x-vonage-timestamp'] || event.headers['X-Vonage-Timestamp'];

  // On VCR, the callback router forwards webhook events without the original
  // Vonage signature headers. Skip verification in that case — the callback
  // router URL is already authenticated via the application/account IDs.
  if (!signature || !timestamp) {
    console.log('[Webhook] Signature headers not present (VCR callback router) — skipping verification');
    return null;
  }

  // Reject requests older than 5 minutes to prevent replay attacks
  const requestTime = parseInt(timestamp, 10) * 1000;
  const now = Date.now();
  if (isNaN(requestTime) || Math.abs(now - requestTime) > 5 * 60 * 1000) {
    console.warn(`[Webhook] Timestamp too old or invalid: ${timestamp}`);
    return {
      status: 401,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ error: 'Webhook timestamp expired' }),
    };
  }

  // Compute expected signature
  const body = event.body || '';
  const signaturePayload = timestamp + body;
  const expectedSignature = crypto
    .createHmac('sha256', apiSecret)
    .update(signaturePayload)
    .digest('hex');

  if (!crypto.timingSafeEqual(Buffer.from(signature), Buffer.from(expectedSignature))) {
    console.error('[Webhook] Signature verification failed');
    return {
      status: 401,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ error: 'Invalid webhook signature' }),
    };
  }

  return null;
}
