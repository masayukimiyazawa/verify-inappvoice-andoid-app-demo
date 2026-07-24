import http from 'http';
import { handler } from './index.js';
import { config } from './config.js';
import { tokenGenerate } from '@vonage/jwt';
import { connectMongo, getDb } from './db.js';

const port = Number(process.env.VCR_PORT) || 3000;

async function configureNCCOUrl() {
  const baseUrl = config.app.domain
    || (process.env.VCR_HOST ? `https://${process.env.VCR_HOST}` : '')
    || 'http://localhost:3000';
  if (!config.vonage.applicationId || !config.vonage.privateKey) {
    console.warn('[Startup] Skipping NCCO config: missing application config');
    return;
  }
  const nccoUrl = `${baseUrl}/api/voice/ncco`;
  const eventsUrl = `${baseUrl}/webhooks/voice-events`;
  try {
    const jwt = tokenGenerate(config.vonage.applicationId, config.vonage.privateKey, {
      acl: { paths: { '/*/applications/**': {} } },
    });
    const credentials = Buffer.from(`${config.vonage.apiKey}:${config.vonage.apiSecret}`).toString('base64');
    const resp = await fetch(`https://api.nexmo.com/v2/applications/${config.vonage.applicationId}`, {
      method: 'PUT',
      headers: {
        'Authorization': `Basic ${credentials}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        name: 'VoiceVerify',
        capabilities: {
          voice: {
            webhooks: {
              answer_url: {
                address: nccoUrl,
                http_method: 'POST',
              },
              event_url: {
                address: eventsUrl,
                http_method: 'POST',
              },
            },
            signed_callbacks: false,
          },
          rtc: {
            webhooks: {
              event_url: {
                address: eventsUrl,
                http_method: 'POST',
              },
            },
          },
        },
      }),
    });
    const data = await resp.json() as any;
    if (resp.ok) {
      console.log(`[Startup] NCCO URL configured: ${nccoUrl}`);
    } else {
      console.warn(`[Startup] NCCO config response (${resp.status}):`, JSON.stringify(data).slice(0, 200));
    }
  } catch (e: any) {
    console.warn(`[Startup] NCCO config error:`, e.message);
  }
}

const server = http.createServer(async (req, res) => {
  const path = req.url?.split('?')[0] || '/';

  if (path === '/api/debug') {
    try {
      await connectMongo();
      const db = getDb();
      const users = db.collection('phone_users');
      const allUsers = await users.find({}).toArray();
      const mask = (s: string) => s ? `${s.slice(0, 4)}...${s.slice(-4)}` : '(empty)';

      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({
        config: {
          vonage_application_id: config.vonage.applicationId ? `${config.vonage.applicationId.slice(0, 8)}...` : '(empty)',
          vonage_phone_number: config.vonage.phoneNumber || '(empty)',
          allowed_phone_number: config.app.allowedPhoneNumber ? mask(config.app.allowedPhoneNumber) : '(empty)',
          mongodb_uri: config.mongodb.uri ? mask(config.mongodb.uri) : '(empty)',
          mongodb_db: config.mongodb.dbName || '(empty)',
          domain: config.app.domain || '(empty)',
        },
        users: allUsers.map((u: any) => ({
          phoneNumber: u.phoneNumber,
          isInAppAvailable: u.isInAppAvailable,
          isVerified: u.isVerified,
        })),
      }, null, 2));
    } catch (err: any) {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: err.message }));
    }
    return;
  }

  let body = '';
  for await (const chunk of req) body += chunk;

  const event = {
    path: req.url?.split('?')[0] || '/',
    method: req.method || 'GET',
    headers: Object.fromEntries(Object.entries(req.headers).map(([k, v]) => [k, Array.isArray(v) ? v[0] : v || ''])),
    body: body || undefined,
    query: Object.fromEntries(new URL(req.url || '/', `http://localhost`).searchParams),
    requestUUID: req.headers['x-request-uuid'] as string || '',
  };

  try {
    const result = await handler(event as any);
    res.writeHead(result.status || 200, result.headers || { 'Content-Type': 'application/json' });
    res.end(result.body || '');
  } catch (err: any) {
    console.error('Server error:', err);
    res.writeHead(500, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ error: 'Internal Server Error' }));
  }
});

server.listen(port, async () => {
  console.log(`Server running on port ${port}`);
  // Only auto-configure webhooks when APP_DOMAIN is set (local dev with tunnel).
  // On VCR the webhook URLs must be configured manually via Dashboard or CLI
  // to avoid overwriting with an incorrect fallback URL.
  if (config.app.domain) {
    await configureNCCOUrl();
  } else {
    console.log('[Startup] APP_DOMAIN not set — skipping NCCO auto-config');
  }
});
