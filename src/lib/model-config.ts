/** Server-only credential value. Never serialize this interface into client state. */
export interface ModelConfig {
  baseUrl: string;
  model: string;
  apiKey: string;
}
