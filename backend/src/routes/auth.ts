/**
 * Authentication Routes
 * - POST /api/auth/send-otp  → Send SMS verification code (Vonage Verify V2)
 * - POST /api/auth/verify-otp → Verify the OTP code
 * - GET  /api/auth/users      → List verified users
 */

import crypto from 'crypto';
import { connectMongo, getDb } from '../db.js';
import { config } from '../config.js';
import { PhoneUser } from '../models/PhoneUser.js';
import { VCREvent, VRResponse } from '../types/vcr.js';
import { json, parseBody, handleCORS } from '../middleware/cors.js';
import { tokenGenerate } from '@vonage/jwt';

// Vonage Verify V2: E.164 format - accept with or without leading + (strip before sending to API)
const PHONE_REGEX = /^\+?[1-9]\d{1,14}$/;

function base64url(str: string): string {
  return Buffer.from(str).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

function generateVerifyJWT(): string {
  const privateKey = config.vonage.privateKey;
  if (!privateKey) {
    throw new Error('VONAGE_PRIVATE_KEY is not set or empty');
  }
  return tokenGenerate(config.vonage.applicationId, privateKey, {
    ttl: 3600,
    aud: 'api.nexmo.com',
  });
}

async function verifyV2Request(to: string): Promise<any> {
  const jwt = generateVerifyJWT();
  const resp = await fetch('https://api.nexmo.com/v2/verify', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${jwt}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      brand: config.vonage.brandName,
      code_length: 6,
      workflow: [{ channel: 'sms', to }],
    }),
  });
  if (!resp.ok) {
    const err = await resp.text();
    throw new Error(`Verify V2 request failed (${resp.status}): ${err}`);
  }
  return resp.json();
}

async function verifyV2Check(requestId: string, code: string): Promise<any> {
  const jwt = generateVerifyJWT();
  const resp = await fetch(`https://api.nexmo.com/v2/verify/${requestId}`, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${jwt}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ code }),
  });
  if (!resp.ok) {
    const err = await resp.text();
    throw new Error(`Verify V2 check failed (${resp.status}): ${err}`);
  }
  return resp.json();
}

function generateJWT(userId: string): string {
  const privateKey = config.vonage.privateKey;
  const now = Math.floor(Date.now() / 1000);
  const expiresIn = 3600;
  const expiration = now + expiresIn;

  const header = { alg: 'RS256', typ: 'JWT' };
  const payload = {
    application_id: config.vonage.applicationId,
    iat: now,
    exp: expiration,
    aud: 'https://voice-ng-vapi.vonage.com',
    user: { id: userId, state: 'Active' },
  };

  const headerB64 = base64url(JSON.stringify(header));
  const payloadB64 = base64url(JSON.stringify(payload));
  const signingInput = `${headerB64}.${payloadB64}`;

  const sign = crypto.createSign('RSA-SHA256');
  sign.update(signingInput);
  const signature = sign.sign(privateKey, 'base64url');

  return `${headerB64}.${payloadB64}.${signature}`;
}

/**
 * POST /api/auth/send-otp
 * Request body: { phoneNumber: string, displayName?: string }
 * 
 * - Creates/updates user in MongoDB
 * - Sends SMS verification code via Vonage Verify V2
 * - SMS sender ID uses Vonage default
 * - Workflow: SMS (primary) + Voice (fallback)
 */
export async function handleSendOTP(event: VCREvent): Promise<VRResponse> {
  try {
    const { phoneNumber: rawPhone, displayName } = parseBody(event);

    if (!rawPhone) {
      return json(400, { error: 'phoneNumber is required' });
    }

    // Accept E.164 with or without leading + (Vonage Verify V2 uses without +)
    const phoneNumber = rawPhone.replace(/^\+/, '');
    const phoneRegex = /^[1-9]\d{1,14}$/;
    if (!phoneRegex.test(phoneNumber)) {
      return json(400, { error: 'phoneNumber must be in E.164 format (e.g., 819012345678 or +819012345678)' });
    }

    // Access control: only the allowed phone number can log in
    const allowedNumber = config.app.allowedPhoneNumber.replace(/^\+/, '');
    if (allowedNumber && phoneNumber !== allowedNumber) {
      return json(403, { error: 'This phone number is not authorized to use this service' });
    }

    await connectMongo();
    const db = getDb();
    const users = db.collection<PhoneUser>('phone_users');
    const now = new Date();

    // Upsert user record
    await users.updateOne(
      { phoneNumber },
      {
        $set: {
          phoneNumber,
          displayName: displayName || null,
          isVerified: false,
          isInAppAvailable: true,
          createdAt: now,
          updatedAt: now,
        },
      },
      { upsert: true }
    );

    // Send verification code via Vonage Verify V2 (E.164 with + for API)
    const verifyResponse = await verifyV2Request(`+${phoneNumber}`);

    const requestId = verifyResponse.request_id;
    const status = verifyResponse.status;

    // Store verify request_id in user document
    await users.updateOne(
      { phoneNumber },
      { $set: { verifyRequestId: requestId, updatedAt: now } }
    );

    console.log(`OTP sent to ${phoneNumber}, request_id: ${requestId}, status: ${status}`);

    return json(200, {
      requestId: requestId,
      message: 'Verification code sent via SMS',
    });
  } catch (error: any) {
    console.error('handleSendOTP error:', error?.message || error);
    return json(500, {
      error: 'Failed to send verification code',
      detail: error?.message || String(error),
    });
  }
}

/**
 * POST /api/auth/verify-otp
 * Request body: { phoneNumber: string, code: string }
 * 
 * - Verifies the OTP code via Vonage Verify V2
 * - On success, marks user as verified in MongoDB
 * - Returns user info for app login
 */
export async function handleVerifyOTP(event: VCREvent): Promise<VRResponse> {
  try {
    const { phoneNumber, code } = parseBody(event);

    if (!phoneNumber || !code) {
      return json(400, { error: 'phoneNumber and code are required' });
    }

    // Access control: only the allowed phone number can verify
    const allowedNumber = config.app.allowedPhoneNumber.replace(/^\+/, '');
    if (allowedNumber && phoneNumber !== allowedNumber) {
      return json(403, { error: 'This phone number is not authorized to use this service' });
    }

    await connectMongo();
    const db = getDb();
    const users = db.collection<PhoneUser>('phone_users');

    const user = await users.findOne({ phoneNumber });
    if (!user) {
      return json(404, { error: 'No verification request found for this phone number' });
    }

    if (!user.verifyRequestId) {
      return json(400, { error: 'No pending verification request' });
    }

    // Verify the code
    const verifyResult = await verifyV2Check(user.verifyRequestId, code);

    console.log(`Verification check for ${phoneNumber}: status=${verifyResult.status}`);

    if (verifyResult.status === 'completed') {
      // User is verified - update in MongoDB
      await users.updateOne(
        { phoneNumber },
        {
          $set: {
            isVerified: true,
            verifiedAt: new Date(),
            updatedAt: new Date(),
          },
        }
      );

      const token = generateJWT(phoneNumber);

      return json(200, {
        verified: true,
        token,
        expires_in: 3600,
        user: {
          phoneNumber: user.phoneNumber,
          displayName: user.displayName,
          isInAppAvailable: user.isInAppAvailable,
        },
      });
    }

    return json(400, {
      verified: false,
      error: 'Invalid verification code',
      status: verifyResult.status,
    });
  } catch (error: any) {
    console.error('handleVerifyOTP error:', error?.message || error);
    return json(500, {
      error: 'Verification check failed',
      detail: error?.message || String(error),
    });
  }
}

/**
 * GET /api/auth/users
 * Returns list of verified users from MongoDB
 */
export async function handleListUsers(): Promise<VRResponse> {
  try {
    await connectMongo();
    const db = getDb();
    const users = db.collection<PhoneUser>('phone_users');
    const verifiedUsers = await users
      .find({ isVerified: true })
      .project<Pick<PhoneUser, 'phoneNumber' | 'displayName' | 'isInAppAvailable' | 'createdAt'>>({
        phoneNumber: 1,
        displayName: 1,
        isInAppAvailable: 1,
        createdAt: 1,
      })
      .toArray();

    return json(200, { users: verifiedUsers });
  } catch (error: any) {
    console.error('handleListUsers error:', error);
    return json(500, { error: 'Failed to list users' });
  }
}

/**
 * Main handler - routes to specific auth endpoints
 */
export async function handler(event: VCREvent): Promise<VRResponse> {
  const cors = await handleCORS(event);
  if (cors) return cors;

  const path = event.path;

  if (path === '/api/auth/send-otp') {
    return handleSendOTP(event);
  }
  if (path === '/api/auth/verify-otp') {
    return handleVerifyOTP(event);
  }
  if (path === '/api/auth/users') {
    return handleListUsers();
  }

  return json(404, { error: 'Auth route not found' }, event);
}
