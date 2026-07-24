import * as authRouter from './routes/auth.js';
import * as voiceRouter from './routes/voice.js';
import * as voiceAuth from './routes/voiceAuth.js';
import { voiceEventHandler } from './webhooks.js';
import { VCREvent, VRResponse } from './types/vcr.js';
import { checkRateLimit, getClientIp } from './middleware/rateLimit.js';
import { verifyJWT } from './middleware/jwtAuth.js';
import { verifyWebhookSignature } from './middleware/webhook.js';

export async function handler(event: VCREvent): Promise<VRResponse> {
  const path = event.path;

  if (path === '/_/health') {
    return { status: 200, headers: { 'Content-Type': 'text/plain' }, body: 'OK' };
  }

  if (event.method === 'OPTIONS') {
    return {
      status: 204,
      headers: {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-API-Key',
      },
    };
  }

  if (path === '/webhooks/voice-events') {
    const webhookError = verifyWebhookSignature(event);
    if (webhookError) return webhookError;
    return voiceEventHandler(event);
  }

  if (event.method === 'POST') {
    const clientIp = getClientIp(event);

    if (path === '/api/auth/send-otp') {
      const result = checkRateLimit(`otp:${clientIp}`, {
        windowMs: 5 * 60 * 1000,
        maxRequests: 5,
      });
      if (!result.allowed) {
        return rateLimitResponse(result.resetAt);
      }
    }

    if (path === '/api/auth/verify-otp') {
      const result = checkRateLimit(`verify:${clientIp}`, {
        windowMs: 5 * 60 * 1000,
        maxRequests: 10,
      });
      if (!result.allowed) {
        return rateLimitResponse(result.resetAt);
      }
    }

    if (path === '/api/voice/call') {
      const result = checkRateLimit(`call:${clientIp}`, {
        windowMs: 60 * 1000,
        maxRequests: 5,
      });
      if (!result.allowed) {
        return rateLimitResponse(result.resetAt);
      }
    }

    if (path === '/api/voice/auth/token' || path === '/api/voice/reconnect') {
      const result = checkRateLimit(`token:${clientIp}`, {
        windowMs: 60 * 1000,
        maxRequests: 10,
      });
      if (!result.allowed) {
        return rateLimitResponse(result.resetAt);
      }
    }
  }

  if (path === '/api/auth/send-otp') return authRouter.handleSendOTP(event);
  if (path === '/api/auth/verify-otp') return authRouter.handleVerifyOTP(event);
  if (path === '/api/auth/users') return authRouter.handleListUsers();

  if (path === '/api/voice/ncco') return voiceRouter.handleNCCO(event);

  const jwtResult = verifyJWT(event);
  if (jwtResult.error) return jwtResult.error;
  if (path === '/api/voice/call') return voiceRouter.handleInitiateOutgoingCall(event);
  if (path === '/api/voice/auth/token') return voiceAuth.handler(event);
  if (path === '/api/voice/register') return voiceAuth.handler(event);
  if (path === '/api/voice/push-token') return voiceAuth.handler(event);
  if (path === '/api/voice/reconnect') return voiceAuth.handler(event);

  return {
    status: 404,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ error: 'Not Found' }),
  };
}

function rateLimitResponse(resetAt: number): VRResponse {
  const retryAfter = Math.ceil((resetAt - Date.now()) / 1000);
  return {
    status: 429,
    headers: {
      'Content-Type': 'application/json',
      'Retry-After': String(retryAfter),
    },
    body: JSON.stringify({
      error: 'Too many requests. Please try again later.',
      retryAfter,
    }),
  };
}
