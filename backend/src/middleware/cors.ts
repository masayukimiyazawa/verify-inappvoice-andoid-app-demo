/**
 * CORS utility functions
 * 
 * Provides configurable CORS headers based on allowed origins.
 */

import { config } from '../config.js';
import { VCREvent, VRResponse } from '../types/vcr.js';

function getAllowedOrigin(event: VCREvent): string {
  const allowedOrigins = config.app.allowedOrigins;

  if (allowedOrigins.includes('*')) {
    return '*';
  }

  const requestOrigin = event.headers['origin'] || event.headers['Origin'];

  if (requestOrigin && allowedOrigins.includes(requestOrigin)) {
    return requestOrigin;
  }

  return allowedOrigins[0] || '';
}

export function getCorsHeaders(event: VCREvent): Record<string, string> {
  return {
    'Access-Control-Allow-Origin': getAllowedOrigin(event),
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-API-Key',
  };
}

export async function handleCORS(event: VCREvent): Promise<VRResponse | null> {
  if (event.method === 'OPTIONS') {
    return {
      status: 204,
      headers: getCorsHeaders(event),
    };
  }
  return null;
}

export function json(status: number, data: any, event?: VCREvent): VRResponse {
  const corsHeaders = event ? getCorsHeaders(event) : { 'Access-Control-Allow-Origin': '*' };
  return {
    status,
    headers: {
      'Content-Type': 'application/json',
      ...corsHeaders,
    },
    body: JSON.stringify(data),
  };
}

export function parseBody(event: VCREvent): any {
  if (!event.body || event.body === 'null') return {};
  return JSON.parse(event.body);
}
