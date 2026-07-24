export interface VCREvent {
  headers: Record<string, string>;
  method: string;
  path: string;
  body?: string;
  query?: Record<string, string>;
  params?: Record<string, string>;
}

export interface VRResponse {
  status: number;
  headers: Record<string, string>;
  body?: string;
}
