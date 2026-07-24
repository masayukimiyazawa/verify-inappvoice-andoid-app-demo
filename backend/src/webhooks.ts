/**
 * Webhook event handler entry point
 * 
 * Handles Vonage Voice API event callbacks.
 * Updates call state and InApp availability in MongoDB.
 */

import { connectMongo, getDb } from './db.js';
import { config } from './config.js';
import { PhoneUser } from './models/PhoneUser.js';
import { VCREvent, VRResponse } from './types/vcr.js';
import { json, parseBody } from './middleware/cors.js';

/**
 * Voice Events Webhook
 * 
 * Receives call lifecycle events from Vonage Voice API.
 * Events: call.started, call.stopped, call.failed
 * 
 * Updates:
 * - Call event history in MongoDB
 * - InApp availability status based on call state changes
 */
export async function voiceEventHandler(event: VCREvent): Promise<VRResponse> {
  try {
    const body = parseBody(event);

    const voiceEvent = (body as Record<string, unknown>).event as string;
    const uuid = (body as Record<string, unknown>).uuid as string;
    const to = (body as Record<string, unknown>).to as string;
    const from = (body as Record<string, unknown>).from as string;
    const conversationUuid = (body as Record<string, unknown>).conversationUuid as string;
    const status = (body as Record<string, unknown>).status as string;
    const duration = (body as Record<string, unknown>).duration as string;
    const recordingUrl = (body as Record<string, unknown>).recordingUrl as string;

    if (!voiceEvent || !uuid) {
      console.warn('[VoiceEvent] Missing event or UUID');
      return json(200, { received: true });
    }

    console.log(`[VoiceEvent] event=${voiceEvent}, uuid=${uuid}, to=${to}, from=${from}, status=${status}, duration=${duration}`);

    await connectMongo();
    const db = getDb();

    const callEvents = db.collection('call_events');
    const users = db.collection<PhoneUser>('phone_users');

    await callEvents.insertOne({
      uuid,
      event: voiceEvent,
      to,
      from,
      conversationUuid,
      status,
      duration: duration ? parseInt(duration, 10) : 0,
      recordingUrl,
      createdAt: new Date(),
    });

    console.log(`[VoiceEvent] Recorded event: ${voiceEvent} for UUID ${uuid}`);

    // Only update isInAppAvailable for registered users (phone numbers in the users collection)
    // to avoid accidentally creating/modifying records for Vonage numbers or external callers.
    const allowedNumber = config.app.allowedPhoneNumber;
    const matchesAllowed = to && allowedNumber && (
      to === allowedNumber || to === allowedNumber.replace(/^\+/, '') || `+${to}` === allowedNumber
    );

    if (matchesAllowed) {
      if (voiceEvent === 'call.stopped' || voiceEvent === 'call.failed') {
        await users.updateOne(
          { $or: [{ phoneNumber: to }, { phoneNumber: `+${to}` }, { phoneNumber: to.replace(/^\+/, '') }] },
          {
            $set: {
              isInAppAvailable: false,
              lastCallStatus: status,
              lastCallAt: new Date(),
            },
            $inc: { totalCalls: 1 },
          }
        );
        console.log(`[VoiceEvent] Marked ${to} as not available (status: ${status})`);
      }

      if (voiceEvent === 'call.started' || voiceEvent === 'call.early_media') {
        await users.updateOne(
          { $or: [{ phoneNumber: to }, { phoneNumber: `+${to}` }, { phoneNumber: to.replace(/^\+/, '') }] },
          {
            $set: {
              isInAppAvailable: true,
              lastCallStatus: 'active',
              lastCallAt: new Date(),
            },
          }
        );
        console.log(`[VoiceEvent] Marked ${to} as available (status: ${status})`);
      }
    } else {
      console.log(`[VoiceEvent] Skipping availability update for non-user number: ${to}`);
    }

    return json(200, { received: true }, event);
  } catch (error: any) {
    console.error('[VoiceEvent] Error:', error);
    return json(200, { received: true }, event);
  }
}
