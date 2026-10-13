/** 第一版 RAG 后端协议；开发环境使用 Vite 的 /api 代理。 */
export interface KnowledgeBase {
  id: number;
  name: string;
}

export interface Document {
  id: number;
  kbId: number;
  fileName: string;
  fileType: string;
  fileSize: number;
  status: 'PENDING' | 'PROCESSING' | 'DONE' | 'FAILED';
  errorMsg: string | null;
  chunkCount: number | null;
  uploadedAt: string;
}

export interface Source {
  chunkId?: number;
  /** 命中子块所属父块 id；等于预览接口 chunks[].id，用于按"块"定位 */
  parentId?: number;
  docId: number;
  docName: string;
  pageNum: number | null;
  sectionTitle?: string | null;
  excerpt: string;
  /** 命中子块全文，用于在父块内高亮 */
  matchedContent?: string;
  /** 父块全文 = 引用所属的一整块 */
  parentContent?: string;
  score?: number;
}

export interface ChatSession {
  id: string;
  title: string | null;
  messageCount: number;
  lastActiveAt: string;
  kbIds: number[];
  docId: number | null;
}

export interface ChatMessage {
  id: number;
  role: 'USER' | 'ASSISTANT';
  content: string;
  sources: string | Source[] | null;
}

type ApiResult<T> = { code: number; message: string; data: T };

export async function request<T>(path: string, options?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, options);
  } catch {
    throw new Error('无法连接后端，请确认 RAG 服务已启动');
  }
  const body = await response.json().catch(() => null) as ApiResult<T> | null;
  if (!response.ok || !body || body.code !== 200) {
    throw new Error(body?.message || `请求失败（${response.status}）`);
  }
  return body.data;
}

export function listKnowledgeBases() {
  return request<KnowledgeBase[]>('/kb');
}

export function createKnowledgeBase(name: string) {
  return request<KnowledgeBase>('/kb', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  });
}

export function listDocuments(kbId: number) {
  return request<Document[]>(`/kb/${kbId}/documents`);
}

export function uploadDocument(kbId: number, file: File) {
  const body = new FormData();
  body.append('file', file);
  return request<{ docId: number }>(`/kb/${kbId}/documents`, { method: 'POST', body });
}

export function getDocumentStatus(kbId: number, docId: number) {
  return request<{ status: Document['status']; errorMsg: string | null }>(
    `/kb/${kbId}/documents/${docId}/status`,
  );
}

/** 手动解析已上传文档，失败后可再次提交。 */
export function startDocumentParsing(kbId: number, docId: number) {
  return request<string>(`/kb/${kbId}/documents/${docId}/parse`, { method: 'POST' });
}

export function deleteDocument(kbId: number, docId: number) {
  return request<null>(`/kb/${kbId}/documents/${docId}`, { method: 'DELETE' });
}

export async function downloadDocument(kbId: number, docId: number): Promise<Blob> {
  let response: Response;
  try {
    response = await fetch(`/api/v1/kb/${kbId}/documents/${docId}/download`);
  } catch {
    throw new Error('无法连接后端，请确认 RAG 服务已启动');
  }
  if (!response.ok) throw new Error(`下载失败（${response.status}）`);
  return response.blob();
}

export function chatStreamUrl(question: string, kbIds: number[], sessionId: string | null, docId: number | null) {
  const query = new URLSearchParams({ question, kbIds: kbIds.join(',') });
  if (sessionId) query.set('sessionId', sessionId);
  if (docId !== null) query.set('docId', String(docId));
  return `/api/v1/chat/stream?${query.toString()}`;
}

export function listChatSessions() {
  return request<ChatSession[]>('/chat/sessions');
}

export function getChatMessages(sessionId: string) {
  return request<ChatMessage[]>(`/chat/sessions/${encodeURIComponent(sessionId)}/messages`);
}
