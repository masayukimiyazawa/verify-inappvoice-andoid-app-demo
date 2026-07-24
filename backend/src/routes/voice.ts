/**
 * Voice Routes
 * - NCCO endpoint: Called by Vonage Voice API to determine call routing
 * - POST /api/voice/call: Initiate an outgoing call
 * - Handles InApp Voice routing and PSTN Voice Proxy fallback
 */

import { connectMongo, getDb } from '../db.js';
import { config } from '../config.js';
import { PhoneUser } from '../models/PhoneUser.js';
import { Vonage } from '@vonage/server-sdk';
import { VCREvent, VRResponse } from '../types/vcr.js';
import { json, parseBody, handleCORS } from '../middleware/cors.js';

function vonageClient(): any {
  return new Vonage({
    apiKey: config.vonage.apiKey,
    apiSecret: config.vonage.apiSecret,
    applicationId: config.vonage.applicationId,
    privateKey: config.vonage.privateKey,
  });
}

/**
 * Voice NCCO Handler
 * 
 * Vonage Voice API calls this endpoint when:
 * 1. An inbound call arrives at a Vonage number (traditional inbound)
 * 2. An InApp client initiates a server-side call via serverCall()
 * 
 * Flow:
 * 1. Check if this is a server-initiated call (has context.to)
 * 2. If server-initiated → connect to PSTN destination
 * 3. If inbound → route to InApp Voice or PSTN Voice Proxy
 */
export async function handleNCCO(event: VCREvent): Promise<VRResponse> {
  try {
    const body = parseBody(event);
    const calledNumber = body.to;
    const callingNumber = body.from;
    const requestUUID = body.requestUUID;
    let context: any = body.context || body.customData || {};
    const cd = body.custom_data;
    if (cd) {
      if (typeof cd === 'string') { try { context = JSON.parse(cd); } catch {} }
      else if (typeof cd === 'object') { context = cd; }
    }

    console.log(`[NCCO] Request body keys: ${Object.keys(body).join(', ')}`);
    console.log(`[NCCO] calledNumber=${calledNumber}, callingNumber=${callingNumber}, custom_data_type=${typeof body.custom_data}, custom_data_value=${JSON.stringify(body.custom_data)}, context=${JSON.stringify(context)}`);

    // Detect server-initiated calls: context.to or customData from SDK
    const destinationNumber = context.to || context.destination || context.phoneNumber || '';
    const callType = context.type || '';

    if (destinationNumber && (callType === 'outbound' || callType === 'inapp')) {
      console.log(`[NCCO] Server-initiated outbound call: to=${destinationNumber}, callingNumber=${callingNumber}`);

      // Both `from` and destination must NOT have a + prefix
      const fromNumber = config.vonage.phoneNumber.replace(/^\+/, '');
      const destNumber = destinationNumber.replace(/^\+/, '');

      const ncco = [
        {
          action: 'connect',
          endpoint: [
            {
              type: 'phone',
              number: destNumber,
            },
          ],
          from: fromNumber,
        },
      ];
      console.log(`[NCCO] Response: ${JSON.stringify(ncco)}`);

      return {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(ncco),
      };
    }

    // Handle traditional inbound calls
    if (!calledNumber) {
      return json(200, {
        ncco: [
          {
            action: 'talk',
            text: 'Invalid call. Goodbye.',
          },
        ],
      });
    }

    await connectMongo();
    const db = getDb();
    const users = db.collection<PhoneUser>('phone_users');

    // Look up the user by the allowed phone number.
    // MongoDB may store phone numbers WITH or WITHOUT + prefix depending on code path.
    // Explicit priority: 1) verified with +, 2) verified without +, 3) any with +, 4) any without +
    const allowedNumberRaw = config.app.allowedPhoneNumber;
    const lookupWithPlus = allowedNumberRaw.startsWith('+') ? allowedNumberRaw : `+${allowedNumberRaw}`;
    const lookupWithoutPlus = allowedNumberRaw.replace(/^\+/, '');
    let user = lookupWithPlus ? await users.findOne({ phoneNumber: lookupWithPlus, isVerified: true }) : null;
    if (!user && lookupWithoutPlus) {
      user = await users.findOne({ phoneNumber: lookupWithoutPlus, isVerified: true });
    }
    if (!user && lookupWithPlus) {
      user = await users.findOne({ phoneNumber: lookupWithPlus });
    }
    if (!user && lookupWithoutPlus) {
      user = await users.findOne({ phoneNumber: lookupWithoutPlus });
    }
    console.log(`[NCCO] User lookup: allowed=${allowedNumberRaw}, found=${user?.phoneNumber || 'none'}, inApp=${user?.isInAppAvailable}, verified=${user?.isVerified}`);

    // InApp Voice: route to the logged-in client via connect.app
    if (user && user.isInAppAvailable && user.isVerified) {
      const userE164 = user.phoneNumber.startsWith('+') ? user.phoneNumber : `+${user.phoneNumber}`;
      console.log(`Routing to InApp Voice: user=${userE164}, called=${calledNumber}, calling=${callingNumber}`);

      return json(200, {
        ncco: [
          {
            action: 'connect',
            from: config.vonage.phoneNumber.replace(/^\+/, ''),
            endpoint: [
              {
                type: 'app',
                user: userE164,
              },
            ],
          },
        ],
      });
    }

    // PSTN fallback: call the user's registered phone number
    const pstnNumber = user?.phoneNumber || lookupWithoutPlus;
    if (pstnNumber) {
      console.log(`InApp not available, falling back to PSTN: ${pstnNumber}`);
      const fromNumber = config.vonage.phoneNumber.replace(/^\+/, '');
      return json(200, {
        ncco: [
          {
            action: 'connect',
            from: fromNumber,
            endpoint: [{ type: 'phone', number: pstnNumber.replace(/^\+/, '') }],
          },
        ],
      });
    }

    console.log(`No user or PSTN number found for ${calledNumber}`);
    return json(200, {
      ncco: [
        {
          action: 'talk',
          text: 'No one is available to take your call. Goodbye.',
        },
      ],
    });
  } catch (error: any) {
    console.error('handleNCCO error:', error);
    return json(200, {
      ncco: [
        {
          action: 'talk',
          text: 'Sorry, an error occurred. Goodbye.',
        },
      ],
    });
  }
}

/**
 * POST /api/voice/call
 * Initiate an outgoing voice call to a registered user.
 * 
 * Request body: { to: string (phone number in E.164 format), from?: string }
 * 
 * When the user doesn't have InApp available this will fall back to PSTN.
 */
export async function handleInitiateOutgoingCall(event: VCREvent): Promise<VRResponse> {
  try {
    const { to, from } = parseBody(event);

    if (!to) {
      return json(400, { error: 'to (destination phone number) is required' });
    }

    const phoneRegex = /^\+?[1-9]\d{1,14}$/;
    if (!phoneRegex.test(to)) {
      return json(400, { error: 'Destination must be in E.164 format' });
    }

    const normalizedTo = to.replace(/^\+/, '');

    await connectMongo();
    const db = getDb();
    const users = db.collection<PhoneUser>('phone_users');
    const user = await users.findOne({ phoneNumber: normalizedTo });

    let ncco: any[];

    if (user && user.isInAppAvailable && user.isVerified) {
      // InApp Voice path: Voice API call bridges to InApp client
      ncco = [
        {
          action: 'connect',
          from: config.vonage.applicationId,
          endpoint: [
            {
              type: 'inapp',
              id: `${config.vonage.applicationId}-${user.phoneNumber}`,
            },
          ],
        },
      ];
    } else {
      // PSTN Voice Proxy path: Voice API call bridges to external number
      ncco = [
        {
          action: 'connect',
          from: config.vonage.applicationId,
          endpoint: [
            {
              type: 'phone',
              number: to,
            },
          ],
        },
      ];
    }

    const fromNumber = (from || config.vonage.phoneNumber || config.vonage.brandName).replace(/^\+/, '');
    const baseUrl = config.app.domain || (process.env.VCR_HOST ? `https://${process.env.VCR_HOST}` : '');
    const eventUrl = baseUrl ? `${baseUrl}/webhooks/voice-events` : '';
    const vonage = vonageClient();
    const call = await vonage.voice.createOutboundCall({
      from: {
        type: 'phone',
        number: fromNumber,
      },
      to: [{ type: 'phone', number: normalizedTo }],
      ncco,
      eventUrl: eventUrl ? [eventUrl] : undefined,
    });

    return json(200, {
      callId: call.uuid,
      message: 'Outgoing call initiated',
      isInApp: user && user.isInAppAvailable,
    });
  } catch (error: any) {
    console.error('handleInitiateOutgoingCall error:', error);
    return json(500, {
      error: 'Failed to initiate call',
    });
  }
}

/**
 * Main voice handler
 */
export async function handler(event: VCREvent): Promise<VRResponse> {
  const cors = await handleCORS(event);
  if (cors) return cors;

  const path = event.path;

  // NCCO webhook - triggered by Vonage Voice API on inbound calls
  if (path.startsWith('/api/voice/ncco')) {
    return handleNCCO(event);
  }

  // Outgoing call initiation
  if (path === '/api/voice/call') {
    return handleInitiateOutgoingCall(event);
  }

  return json(404, { error: 'Voice route not found' }, event);
}
