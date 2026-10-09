import { ref } from 'vue'

export type ActionStatus = 'PENDING' | 'SNOOZED' | 'RUNNING' | 'DONE' | 'FAILED' | 'DELETED'

export interface PlanStep { type: string; description: string; target?: string }

export interface InboxAction {
  id: string
  kind: string
  sourceKey: string
  title: string
  summary: string
  evidence: Record<string, unknown>
  plan: PlanStep[]
  params: Record<string, unknown>
  status: ActionStatus
  decision?: string
  decisionReason?: string
  result?: Record<string, unknown>
  error?: string
  createdAt: string
  updatedAt: string
  snoozedUntil?: string
}

export interface ServerStatus {
  state: 'STOPPED' | 'STARTING' | 'RUNNING' | 'STOPPING'
  managed: boolean
  pid?: number
  startedAt?: string
  lastExitCode?: number
  health?: Record<string, unknown>
}

export interface AuditEntry { id: number; at: string; actor: string; role: string; action: string; target?: string; details: Record<string, unknown> }
export interface ApiToken { id: number; role: string; label: string; createdAt: string; lastUsedAt?: string }
export interface Principal { tokenId: number; role: 'OWNER' | 'DEV' | 'VIEWER'; label: string }
export interface CockpitEvent { type: string; payload: unknown; at: string }

const TOKEN_KEY = 'cockpit.token'

export const token = ref<string>(safeGet(TOKEN_KEY))
export const me = ref<Principal | null>(null)

function safeGet(key: string): string {
  try { return localStorage.getItem(key) ?? '' } catch { return '' }
}

export function setToken(value: string) {
  token.value = value.trim()
  try { localStorage.setItem(TOKEN_KEY, token.value) } catch { /* private window */ }
}

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message) }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Authorization: `Bearer ${token.value}` }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await response.text()
  const data = text ? JSON.parse(text) : null
  if (!response.ok) throw new ApiError(response.status, data?.error ?? response.statusText)
  return data as T
}

export const api = {
  me: () => request<Principal>('GET', '/api/me'),
  server: () => request<ServerStatus>('GET', '/api/server'),
  serverLog: (lines = 200) => request<{ lines: string[] }>('GET', `/api/server/log?lines=${lines}`),
  serverStart: () => request<{ started: boolean }>('POST', '/api/server/start'),
  serverStop: (ticks: number) => request<unknown>('POST', `/api/server/stop?ticks=${ticks}`),
  serverRestart: (ticks: number) => request<unknown>('POST', `/api/server/restart?ticks=${ticks}`),
  wikiReload: () => request<unknown>('POST', '/api/server/wiki-reload'),
  inbox: (status?: ActionStatus) => request<InboxAction[]>('GET', status ? `/api/inbox?status=${status}` : '/api/inbox'),
  inboxCounts: () => request<Record<ActionStatus, number>>('GET', '/api/inbox/counts'),
  go: (id: string, params?: Record<string, unknown>) => request<InboxAction>('POST', `/api/inbox/${id}/go`, params ? { params } : {}),
  edit: (id: string, params: Record<string, unknown>) => request<InboxAction>('POST', `/api/inbox/${id}/edit`, { params }),
  remove: (id: string, reason: string) => request<InboxAction>('POST', `/api/inbox/${id}/delete`, { reason }),
  snooze: (id: string, hours: number) => request<InboxAction>('POST', `/api/inbox/${id}/snooze`, { hours }),
  audit: (limit = 100) => request<AuditEntry[]>('GET', `/api/audit?limit=${limit}`),
  tokens: () => request<ApiToken[]>('GET', '/api/tokens'),
  issueToken: (role: string, label: string) => request<{ token: string; record: ApiToken }>('POST', '/api/tokens', { role, label }),
  revokeToken: (id: number) => request<{ revoked: boolean }>('DELETE', `/api/tokens/${id}`),
}

/** Live events; reconnects on its own. The token travels as a query parameter because EventSource has no headers. */
export function subscribe(handler: (event: CockpitEvent) => void): () => void {
  const source = new EventSource(`/api/events?token=${encodeURIComponent(token.value)}`)
  const types = ['inbox.created', 'inbox.updated', 'server.lifecycle', 'server.missing', 'log']
  for (const type of types) {
    source.addEventListener(type, (raw) => {
      const message = raw as MessageEvent
      handler({ type, payload: message.data ? JSON.parse(message.data) : null, at: message.lastEventId })
    })
  }
  return () => source.close()
}
