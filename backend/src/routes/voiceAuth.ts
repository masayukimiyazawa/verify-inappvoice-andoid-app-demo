/**
 * Voice Auth Routes
 * - POST /api/voice/auth/token: Generate Vonage InApp Voice JWT token
 * - POST /api/voice/register: Register/unregister app user for InApp Voice
 * - POST /api/voice/push-token: Register/unregister device push token for Firebase
 * - POST /api/voice/reconnect: Get a fresh token for reconnecting
 */

import crypto from 'crypto';
import { connectMongo, getDb } from '../db.js';
import { config } from '../config.js';
import { PhoneUser } from '../models/PhoneUser.js';
import { VCREvent, VRResponse } from '../types/vcr.js';
import { json, parseBody, handleCORS } from '../middleware/cors.js';
import { tokenGenerate } from '@vonage/jwt';

const PHONE_REGEX = /^\+?[1-9]\d{1,14}$/;

function ensureE164(phone: string): string {
  return phone.startsWith('+') ? phone : `+${phone}`;
}

function hashToken(token: string): string {
  return crypto.createHash('sha256').update(token).digest('hex').slice(0, 16);
}

/**
 * POST /api/voice/auth/token
 * 
 * Generates a Vonage InApp Voice JWT token for client authentication.
 * The JWT is signed with the Vonage application's private key.
 * 
 * Request body: { userId: string }
 * Response: { token: string, expires_in: number }
 * 
 * The JWT claims:
 * - application_id: Vonage application ID
 * - private_key: Path or content (used for signing)
 * - aud: Vonage API endpoint
 * - exp/IAT: Token validity window
 * - session_id: Unique session identifier
 * - device_id: Device identifier (optional)
 */
export async function handleGenerateToken(event: VCREvent): Promise<VRResponse> {
  try {
    const { userId, sessionId, deviceId } = parseBody(event);

    if (!userId) {
      return json(400, { error: 'userId is required' });
    }

    if (!PHONE_REGEX.test(userId)) {
      return json(400, { error: 'userId must be a valid phone number in E.164 format' });
    }

    const e164UserId = ensureE164(userId);

    // Access control: only the allowed phone number can generate voice tokens
    const allowedNumber = config.app.allowedPhoneNumber;
    if (allowedNumber && e164UserId !== allowedNumber && userId !== allowedNumber.replace(/^\+/, '')) {
      return json(403, { error: 'This phone number is not authorized to use this service' });
    }

    // Register user in Vonage via direct API call
    try {
      const now = Math.floor(Date.now() / 1000);
      const userToken = tokenGenerate(config.vonage.applicationId, config.vonage.privateKey, {
        sub: 'sysadmin',
        exp: now + 120,
        iat: now,
        jti: generateUUID(),
        acl: { paths: { '/*/users/**': { methods: ['POST', 'GET', 'PUT', 'DELETE'] } } },
      });
      const resp = await fetch('https://api-ap.vonage.com/v1/users', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${userToken}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ name: e164UserId }),
      });
      const respBody = await resp.text();
      if (resp.ok) {
        const user = JSON.parse(respBody);
        console.log(`[VoiceAuth] Vonage user registered: ${user.id || e164UserId}`);
      } else if (resp.status === 409 || respBody.includes('already exists')) {
        console.log(`[VoiceAuth] Vonage user already exists: ${e164UserId.slice(0, 4)}***`);
      } else {
        console.warn(`[VoiceAuth] Vonage user registration error: status=${resp.status} body=${respBody.slice(0, 300)}`);
        console.log(`[VoiceAuth] Proceeding anyway for userId=${e164UserId.slice(0, 4)}***`);
      }
    } catch (e: any) {
      console.warn(`[VoiceAuth] Vonage user registration exception: ${e.message}`);
      console.log(`[VoiceAuth] Proceeding anyway for userId=${e164UserId.slice(0, 4)}***`);
    }

    // Generate authenticated session and device IDs
    const sessionUUID = generateUUID();
    const deviceUUID = generateUUID();

    const expiresIn = 86400; // 24 hours (increased from 1h for testing stability)
    const now = Math.floor(Date.now() / 1000);

    const token = tokenGenerate(config.vonage.applicationId, config.vonage.privateKey, {
      sub: e164UserId,
      exp: now + expiresIn,
      acl: {
        paths: {
          '/*/sessions/**': { methods: ['POST'] },
          '/*/conversations/*': { methods: ['GET'] },
          '/*/conversations/*/rtc/*/answer': { methods: ['POST'] },
          '/*/conversations/*/rtc/*/offer/*': { methods: ['POST'] },
          '/*/conversations/*/members/*': { methods: ['PUT', 'DELETE'] },
          '/*/knocking/**': { methods: ['POST', 'DELETE'] },
          '/*/legs/**': { methods: ['POST', 'GET'] },
          '/*/v2/rtc/**': { methods: ['POST', 'GET'] },
        },
      },
    });

    const decodedClaim = JSON.parse(Buffer.from(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString());
    console.log(`[VoiceAuth] Generated token for userId=${e164UserId.slice(0, 4)}***, claims:`, JSON.stringify(decodedClaim));

    // Store token hash in MongoDB for tracking
    await connectMongo();
    const db = getDb();
    const userTokens = db.collection('user_tokens');

    const expiration = now + expiresIn;

    await userTokens.insertOne({
      userId: e164UserId,
      tokenHash: hashToken(token),
      sessionId: sessionUUID,
      deviceId: deviceUUID,
      issuedAt: new Date(now * 1000),
      expiresAt: new Date(expiration * 1000),
      used: false,
    });

    return json(200, {
      token,
      expires_in: expiresIn,
      session_id: sessionUUID,
      device_id: deviceUUID,
    });
  } catch (error: any) {
    console.error('[VoiceAuth] Token generation error:', error);
    return json(500, {
      error: 'Failed to generate token',
    });
  }
}

/**
 * POST /api/voice/register
 * 
 * Registers or unregisters an app user for InApp Voice.
 * This tells Vonage whether the user is available for InApp calls.
 * 
 * Request body: { userId: string, action: 'register' | 'unregister' }
 */
export async function handleRegisterUser(event: VCREvent): Promise<VRResponse> {
  try {
    const { userId, action } = parseBody(event);

    if (!userId || !action) {
      return json(400, { error: 'userId and action are required' });
    }

    if (action !== 'register' && action !== 'unregister') {
      return json(400, { error: 'action must be "register" or "unregister"' });
    }

    if (!PHONE_REGEX.test(userId)) {
      return json(400, { error: 'userId must be a valid phone number in E.164 format' });
    }

    const e164UserId = ensureE164(userId);

    await connectMongo();
    const db = getDb();
    const users = db.collection<PhoneUser>('phone_users');

    const result = await users.updateOne(
      { phoneNumber: e164UserId },
      {
        $set: {
          isInAppAvailable: action === 'register',
          updatedAt: new Date(),
        },
        $setOnInsert: {
          phoneNumber: e164UserId,
          isVerified: true,
          createdAt: new Date(),
          updatedAt: new Date(),
        },
      },
      { upsert: true }
    );

    console.log(`[VoiceAuth] ${e164UserId.slice(0, 4)}*** ${action}d`);

    return json(200, {
      success: true,
      userId: e164UserId,
      action,
      isInAppAvailable: action === 'register',
    });
  } catch (error: any) {
    console.error('[VoiceAuth] Registration error:', error);
    return json(500, {
      error: 'Failed to register/unregister user',
    });
  }
}

/**
 * POST /api/voice/push-token
 * 
 * Registers or removes a Firebase device push token for Vonage push notifications.
 * This allows Vonage to send push notifications when the InApp SDK is disconnected.
 * 
 * Request body: { userId: string, deviceId: string, action: 'register' | 'remove' }
 */
export async function handlePushToken(event: VCREvent): Promise<VRResponse> {
  try {
    const body = parseBody(event);
    const userId = body.userId;
    const deviceId = body.deviceId;
    const action = body.action;

    if (!userId) {
      return json(400, { error: 'userId is required' });
    }

    if (!PHONE_REGEX.test(userId)) {
      return json(400, { error: 'userId must be a valid phone number in E.164 format' });
    }

    const e164UserId = ensureE164(userId);

    await connectMongo();
    const db = getDb();

    if (action === 'remove') {
      await db.collection('push_tokens').deleteOne({ userId: e164UserId });
      console.log(`[VoiceAuth] Push token removed for userId=${e164UserId.slice(0, 4)}***`);
      return json(200, { success: true, userId: e164UserId, action: 'removed' });
    }

    if (!deviceId) {
      return json(400, { error: 'deviceId is required for register action' }, event);
    }

    const pushToken = body.pushToken || '';

    await db.collection('push_tokens').updateOne(
      { userId: e164UserId },
      {
        $set: {
          userId: e164UserId,
          deviceId,
          pushToken,
          createdAt: new Date(),
          updatedAt: new Date(),
        },
      },
      { upsert: true }
    );

    console.log(`[VoiceAuth] Push token registered for userId=${e164UserId.slice(0, 4)}***, deviceId=${deviceId.slice(0, 4)}***`);

    return json(200, { success: true, userId: e164UserId, deviceId });
  } catch (error: any) {
    console.error('[VoiceAuth] Push token error:', error);
    return json(500, {
      error: 'Failed to manage push token',
    });
  }
}

/**
 * POST /api/voice/reconnect
 * 
 * Returns a fresh JWT token for clients to reconnect to Vonage LVN.
 * This is used when the app resumes from background.
 * 
 * Request body: { userId: string }
 * Response: { token: string, expires_in: number }
 */
export async function handleReconnect(event: VCREvent): Promise<VRResponse> {
  try {
    const { userId } = parseBody(event);

    if (!userId) {
      return json(400, { error: 'userId is required' });
    }

    if (!PHONE_REGEX.test(userId)) {
      return json(400, { error: 'userId must be a valid phone number in E.164 format' });
    }

    const e164UserId = ensureE164(userId);

    // Access control: only the allowed phone number can reconnect
    const allowedNumber = config.app.allowedPhoneNumber;
    if (allowedNumber && e164UserId !== allowedNumber && userId !== allowedNumber.replace(/^\+/, '')) {
      return json(403, { error: 'This phone number is not authorized to use this service' });
    }

    const expiresIn = 86400; // 24 hours
    const now = Math.floor(Date.now() / 1000);

    const sessionUUID = generateUUID();
    const deviceUUID = generateUUID();

    const token = tokenGenerate(config.vonage.applicationId, config.vonage.privateKey, {
      sub: e164UserId,
      exp: now + expiresIn,
      acl: {
        paths: {
          '/*/sessions/**': { methods: ['POST'] },
          '/*/conversations/*': { methods: ['GET'] },
          '/*/conversations/*/rtc/*/answer': { methods: ['POST'] },
          '/*/conversations/*/rtc/*/offer/*': { methods: ['POST'] },
          '/*/conversations/*/members/*': { methods: ['PUT', 'DELETE'] },
          '/*/knocking/**': { methods: ['POST', 'DELETE'] },
          '/*/legs/**': { methods: ['POST', 'GET'] },
          '/*/v2/rtc/**': { methods: ['POST', 'GET'] },
        },
      },
    });

    console.log(`[VoiceAuth] Reconnect token generated for userId=${e164UserId.slice(0, 4)}***, sessionId=${sessionUUID.slice(0, 8)}`);

    return json(200, {
      token,
      expires_in: expiresIn,
      session_id: sessionUUID,
      device_id: deviceUUID,
    });
  } catch (error: any) {
    console.error('[VoiceAuth] Reconnect error:', error);
    return json(500, {
      error: 'Failed to generate reconnect token',
    });
  }
}

/**
 * Generate a cryptographically secure UUID v4.
 */
function generateUUID(): string {
  return crypto.randomUUID();
}

/**
 * Main voice auth handler
 */
export async function handler(event: VCREvent): Promise<VRResponse> {
  const cors = await handleCORS(event);
  if (cors) return cors;

  const path = event.path;

  // JWT token generation
  if (path === '/api/voice/auth/token') {
    return handleGenerateToken(event);
  }

  // User registration for InApp Voice
  if (path === '/api/voice/register') {
    return handleRegisterUser(event);
  }

  // Push token registration
  if (path === '/api/voice/push-token') {
    return handlePushToken(event);
  }

  // Reconnect token
  if (path === '/api/voice/reconnect') {
    return handleReconnect(event);
  }

  return json(404, { error: 'Voice auth route not found' }, event);
}
