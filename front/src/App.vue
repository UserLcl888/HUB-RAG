<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import KnowledgeGraph from './views/KnowledgeGraph.vue'
import DocumentReview from './views/DocumentReview.vue'
import MessageBody from './components/MessageBody.vue'
import SourceCards from './components/SourceCards.vue'
import {
  chatStreamUrl, createKnowledgeBase, deleteDocument, downloadDocument,
  getChatMessages, getDocumentStatus, listChatSessions,
  listDocuments, listKnowledgeBases, startDocumentParsing, uploadDocument,
  type ChatMessage as SavedChatMessage,
  type ChatSession as SavedChatSession,
  type Document as RagDocument,
  type Source,
} from './api/rag'
import {
  ArrowDown, ArrowRight, ArrowUp, BookOpen, Check, ChevronDown,
  Clock3, CloudUpload, FileCheck2, FilePlus2, FileText, FolderOpen,
  MessageCircle, Moon, MoreHorizontal, Plus, Search, ShieldCheck, Sparkles,
  Sprout, Sun, Tags, Upload, X, LoaderCircle,
} from '@lucide/vue'

type PolicyFile = {
  id: number
  name: string
  type: string
  date: string
  size: string
  tags: string[]
  color: string
  status: RagDocument['status']
  errorMsg: string | null
  pages: number
}

const route = useRoute()
const router = useRouter()
const activePage = computed(() => route.path === '/documents' ? 'documents' : route.path === '/knowledge' ? 'knowledge' : route.path === '/review' ? 'review' : 'home')
const searchText = ref(typeof route.query.tag === 'string' ? route.query.tag : '')
const librarySearchInput = ref<HTMLInputElement | null>(null)
const selectedFilter = ref('全部文件')
const sortOrder = ref('最新上传')
const fileView = ref<'list' | 'grid'>('list')
const question = ref('')
const chatSession = ref(0)
const messageArea = ref<HTMLElement | null>(null)
const questionInput = ref<HTMLTextAreaElement | null>(null)
const toast = ref('')
const showUpload = ref(route.path === '/documents' && route.query.upload === '1')
const ragKnowledgeBaseId = ref<number | null>(null)
const ragDocuments = ref<RagDocument[]>([])
const backendEnabled = ref(false)
const chatSessionId = ref<string | null>(null)
const chatSessions = ref<SavedChatSession[]>([])
const loadingHistory = ref(false)
const showMobileHistory = ref(false)
const showDocumentPicker = ref(false)
const loadingDocumentPicker = ref(false)
const pendingDocumentId = ref<number | null>(null)
const selectedDocumentId = ref<number | null>(null)
const asking = ref(false)
const parsingRequests = ref(new Set<number>())
let activeStream: EventSource | null = null
let typewriterFrame = 0
let statusTimer: number | undefined
let toastTimer: number | undefined

const allFiles = computed(() => ragDocuments.value.map(toPolicyFile))
const parsedDocuments = computed(() => ragDocuments.value.filter(document => document.status === 'DONE'))
const selectedDocument = computed(() => parsedDocuments.value.find(document => document.id === selectedDocumentId.value) ?? null)
const sessionDocumentUnavailable = computed(() => Boolean(
  chatSessionId.value && !selectedDocument.value,
))
const selectionGreeting = computed(() => selectedDocument.value
  ? `你当前选择的是《${selectedDocument.value.fileName}》。你想问什么？`
  : '')
const standardFileCount = computed(() => allFiles.value.filter(file => file.type === '标准规范').length)
const visibleFiles = computed(() => {
  const query = searchText.value.trim().toLowerCase()
  let result = allFiles.value.filter(file => {
    const matchesQuery = !query || `${file.name} ${file.tags.join(' ')}`.toLowerCase().includes(query)
    const matchesFilter = selectedFilter.value === '全部文件' || file.type === selectedFilter.value
    return matchesQuery && matchesFilter
  })
  if (sortOrder.value === '文件名 A-Z') result = [...result].sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'))
  else if (sortOrder.value === '文件名 Z-A') result = [...result].sort((a, b) => b.name.localeCompare(a.name, 'zh-CN'))
  return result
})
const CHAT_DOCUMENT_STORAGE_KEY = 'farmland-chat-document-ids'
const chatMessages = ref<{
  role: 'user' | 'assistant'
  text: string
  sources?: Source[]
  streaming?: boolean
  /** 服务端已经到达的完整回答；打字机按帧从这里揭示文本 */
  rawText?: string
  /** 已揭示的字符数 */
  shown?: number
  /** 服务端已结束（done/error），文本揭示完毕后才切换到结构化渲染 */
  answerDone?: boolean
}[]>([])

function getChatDocumentIds(): Record<string, number> {
  try {
    const stored: unknown = JSON.parse(localStorage.getItem(CHAT_DOCUMENT_STORAGE_KEY) ?? '{}')
    if (stored && typeof stored === 'object' && !Array.isArray(stored)) {
      return stored as Record<string, number>
    }
  } catch {
    // 本地存储不可用时仍允许在当前页面选择文档提问
  }
  return {}
}

function saveChatDocumentId(sessionId: string, documentId: number) {
  try {
    localStorage.setItem(CHAT_DOCUMENT_STORAGE_KEY, JSON.stringify({
      ...getChatDocumentIds(),
      [sessionId]: documentId,
    }))
  } catch {
    // 浏览器禁止本地存储时不影响当前会话
  }
}

function getChatDocumentId(sessionId: string) {
  const storedId = getChatDocumentIds()[sessionId]
  return typeof storedId === 'number' ? storedId : null
}

function cleanCitationMarkers(text: string) {
  return text
    .replace(/[（(]?[ \t]*来源[ \t]*[:：][ \t]*(?:\[参考\d+\][ \t]*)+[）)]?/g, '')
    .replace(/\[参考\d+\]/g, '')
}

function restoreChatMessage(message: SavedChatMessage) {
  let sources: Source[] = []
  if (message.sources) {
    try {
      const parsed: unknown = typeof message.sources === 'string'
        ? JSON.parse(message.sources) : message.sources
      if (Array.isArray(parsed)) {
        sources = parsed.filter((source): source is Source =>
          typeof source?.docName === 'string' && source.docName.length > 0)
      }
    } catch {
      // 历史来源格式异常时仍展示回答正文
    }
  }
  const isUser = message.role === 'USER'
  return {
    role: isUser ? 'user' as const : 'assistant' as const,
    text: isUser ? message.content : cleanCitationMarkers(message.content),
    // 来源不再拼进正文，作为独立字段交给 SourceCards 渲染
    sources: isUser ? [] : sources,
    streaming: false,
  }
}

async function loadChatSessions() {
  try {
    const sessions = await listChatSessions()
    chatSessions.value = sessions.map(session => ({
      ...session,
      kbIds: normalizeKnowledgeBaseIds(session.kbIds),
      docId: typeof session.docId === 'number' ? session.docId : null,
    }))
  } catch (error) {
    notify(error instanceof Error ? error.message : '历史对话加载失败')
  }
}

function normalizeKnowledgeBaseIds(value: unknown): number[] {
  if (Array.isArray(value)) {
    return value
      .map(item => typeof item === 'number' ? item : Number(item))
      .filter(item => Number.isFinite(item))
  }
  if (typeof value === 'string') {
    return value
      .replace(/^\[|\]$/g, '')
      .split(',')
      .map(item => Number(item.trim()))
      .filter(item => Number.isFinite(item))
  }
  return []
}

function findSession(sessionId: string) {
  return chatSessions.value.find(session => session.id === sessionId) ?? null
}

function resolveSessionDocument(session: SavedChatSession) {
  const documentsInScope = parsedDocuments.value.filter(document => session.kbIds.includes(document.kbId))
  const backendDocument = typeof session.docId === 'number'
    ? documentsInScope.find(document => document.id === session.docId)
    : null
  if (session.docId !== null && session.docId !== undefined) {
    return backendDocument ?? null
  }
  const localDocumentId = getChatDocumentId(session.id)
  const localDocument = typeof localDocumentId === 'number'
    ? documentsInScope.find(document => document.id === localDocumentId)
    : null
  return localDocument ?? documentsInScope[0] ?? null
}

async function openChatSession(sessionId: string) {
  if (asking.value) {
    notify('请等待当前回答完成后再切换会话')
    return
  }
  if (loadingHistory.value || (chatSessionId.value === sessionId && chatMessages.value.length)) return
  const requestVersion = ++chatSession.value
  loadingHistory.value = true
  try {
    if (!backendEnabled.value || !ragDocuments.value.length) {
      await loadRagDocuments()
    }
    const messages = await getChatMessages(sessionId)
    if (requestVersion !== chatSession.value) return
    const session = findSession(sessionId)
    chatSessionId.value = sessionId
    selectedDocumentId.value = session
      ? resolveSessionDocument(session)?.id ?? null
      : getChatDocumentId(sessionId)
    chatMessages.value = messages.map(restoreChatMessage)
    question.value = ''
    showMobileHistory.value = false
    void scrollToLatestMessage()
  } catch (error) {
    if (requestVersion === chatSession.value) {
      notify(error instanceof Error ? error.message : '历史对话加载失败')
    }
  } finally {
    if (requestVersion === chatSession.value) loadingHistory.value = false
  }
}

function toPolicyFile(document: RagDocument): PolicyFile {
  const size = document.fileSize > 1024 * 1024
    ? `${(document.fileSize / 1024 / 1024).toFixed(1)} MB`
    : `${(document.fileSize / 1024).toFixed(1)} KB`
  const status = document.status === 'DONE' ? '已解析' : document.status === 'FAILED' ? '解析失败' : document.status === 'PROCESSING' ? '解析中' : '待解析'
  const type = document.fileName.includes('规范') || document.fileName.includes('标准') ? '标准规范' : '政策文件'
  return {
    id: document.id,
    name: document.fileName,
    type,
    date: new Date(document.uploadedAt).toLocaleDateString('zh-CN'),
    size,
    tags: document.status === 'DONE' ? ['已解析', '知识库'] : [status],
    color: document.status === 'FAILED' ? 'failed' : 'mint',
    status: document.status,
    errorMsg: document.errorMsg,
    pages: 0,
  }
}

async function loadRagDocuments() {
  try {
    const knowledgeBases = await listKnowledgeBases()
    ragKnowledgeBaseId.value = knowledgeBases[0]?.id ?? null
    ragDocuments.value = knowledgeBases.length
      ? (await Promise.all(knowledgeBases.map(base => listDocuments(base.id)))).flat()
      : []
    ragDocuments.value.sort((a, b) => Date.parse(b.uploadedAt) - Date.parse(a.uploadedAt))
    backendEnabled.value = true
    if (ragDocuments.value.some(doc => doc.status === 'PROCESSING')) {
      startStatusPolling()
    }
  } catch (error) {
    backendEnabled.value = false
    ragDocuments.value = []
    notify(error instanceof Error ? error.message : '后端未连接')
  }
}

function startStatusPolling() {
  if (statusTimer) return
  statusTimer = window.setInterval(async () => {
    const pending = ragDocuments.value.filter(doc => doc.status === 'PROCESSING')
    if (!pending.length) {
      window.clearInterval(statusTimer)
      statusTimer = undefined
      return
    }
    try {
      await Promise.all(pending.map(async doc => {
        const result = await getDocumentStatus(doc.kbId, doc.id)
        doc.status = result.status
        doc.errorMsg = result.errorMsg
      }))
      if (!ragDocuments.value.some(doc => doc.status === 'PROCESSING')) {
        await loadRagDocuments()
      }
    } catch {
      window.clearInterval(statusTimer)
      statusTimer = undefined
      notify('无法获取文档解析状态')
    }
  }, 2500)
}

watch(() => route.fullPath, () => {
  showUpload.value = route.path === '/documents' && route.query.upload === '1'
  showMobileHistory.value = false
  if (route.path === '/documents') {
    searchText.value = typeof route.query.tag === 'string' ? route.query.tag : ''
  }
})

onMounted(() => {
  restoreWhiteMode()
  void loadRagDocuments()
  void loadChatSessions()
})

function handlePageKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    showUpload.value = false
    showDocumentPicker.value = false
  }
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k' && activePage.value === 'documents') {
    event.preventDefault()
    librarySearchInput.value?.focus()
  }
}

onMounted(() => window.addEventListener('keydown', handlePageKeydown))
onUnmounted(() => window.removeEventListener('keydown', handlePageKeydown))
onUnmounted(() => {
  activeStream?.close()
  window.clearInterval(statusTimer)
  if (typewriterFrame) cancelAnimationFrame(typewriterFrame)
})

function notify(message: string) {
  toast.value = message
  window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => toast.value = '', 2600)
}
function navigate(path: string) {
  router.push(path)
}
function openUploadFromHome() {
  router.push({ path: '/documents', query: { upload: '1' } })
}

/**
 * 首页中部的"大家都在问"与"热门标签"：**写死的展示内容**。
 * 点击只切换选中高亮（再点一次取消），不跳转、不填充输入框、不发起提问。
 */
const hotQuestions = [
  '隐蔽工程（如管道）如何进行采集与调查？',
  '工程设施采集时，如何处理与地块的相对位置关系？',
  '高标准农田项目验收需要准备哪些材料？',
  '树木占用耕地的判定标准是什么？',
  '哪些关联工程设施需要采集调查？',
]
const hotTags = [
  '高标准农田', '农田建设', '质量验收', '设计参考', '项目管理',
  '技术标准', '档案管理', '政策文件', 'GB/T30600-2022',
]
const activeHotQuestion = ref<string | null>(null)
const activeHotTag = ref<string | null>(null)
function toggleHotQuestion(item: string) {
  activeHotQuestion.value = activeHotQuestion.value === item ? null : item
}
function toggleHotTag(tag: string) {
  activeHotTag.value = activeHotTag.value === tag ? null : tag
}
/** 热门问题序号：1 → "01" */
function hotIndex(index: number) {
  return String(index + 1).padStart(2, '0')
}

/**
 * 主题切换：深色（默认）↔ 白色。
 * 本工程样式是硬编码色值（451 处 hex，只有 9 个 CSS 变量），无法靠改变量换肤，
 * 因此对 .app-shell 整体做色调翻转；弹窗与提示已放在 .app-shell 之外，
 * 不会受 filter 影响（filter 会把祖先变成 position:fixed 的包含块）。
 */
const whiteMode = ref(false)
function applyWhiteMode(value: boolean) {
  whiteMode.value = value
  document.documentElement.dataset.theme = value ? 'white' : 'dark'
  try {
    window.localStorage.setItem('hbnt-theme', value ? 'white' : 'dark')
  } catch {
    // 隐私模式等场景下 localStorage 不可用，忽略即可
  }
}
function toggleWhiteMode() {
  applyWhiteMode(!whiteMode.value)
}
function restoreWhiteMode() {
  let saved: string | null = null
  try {
    saved = window.localStorage.getItem('hbnt-theme')
  } catch {
    saved = null
  }
  whiteMode.value = saved === 'white'
  document.documentElement.dataset.theme = whiteMode.value ? 'white' : 'dark'
}
async function openDocumentPicker() {
  if (showDocumentPicker.value) return
  showDocumentPicker.value = true
  pendingDocumentId.value = null
  showMobileHistory.value = false
  loadingDocumentPicker.value = true
  try {
    await loadRagDocuments()
  } finally {
    loadingDocumentPicker.value = false
  }
}
async function startNewChat(document: RagDocument) {
  activeStream?.close()
  activeStream = null
  asking.value = false
  chatSessionId.value = null
  chatSession.value += 1
  loadingHistory.value = false
  selectedDocumentId.value = document.id
  chatMessages.value = []
  question.value = ''
  showDocumentPicker.value = false
  showMobileHistory.value = false
  await nextTick()
  messageArea.value?.scrollTo({ top: 0, behavior: 'instant' })
  questionInput.value?.focus()
}
async function confirmDocumentSelection() {
  const document = parsedDocuments.value.find(item => item.id === pendingDocumentId.value)
  if (!document || loadingDocumentPicker.value) return
  await router.push('/')
  await startNewChat(document)
}
async function scrollToLatestMessage(behavior: ScrollBehavior = 'smooth') {
  await nextTick()
  messageArea.value?.scrollTo({ top: messageArea.value.scrollHeight, behavior })
}

/**
 * 打字机揭示：SSE 的 token 是成批到达的，直接塞进 text 会一坨一坨地往外蹦，看着很死。
 * 这里按帧从 rawText 里揭示字符——积压越多揭示越快（自适应追赶），
 * 文本全部揭示且服务端已结束时，才把 streaming 置 false 切到结构化渲染。
 */
function pumpTypewriter() {
  if (typewriterFrame) return
  const tick = () => {
    typewriterFrame = 0
    let revealing = false
    for (const message of chatMessages.value) {
      if (message.role !== 'assistant' || message.rawText === undefined) continue
      const target = message.rawText
      let shown = Math.min(message.shown ?? 0, target.length)
      if (shown < target.length) {
        const backlog = target.length - shown
        shown = Math.min(target.length, shown + Math.max(1, Math.ceil(backlog / 18)))
        revealing = true
      }
      message.shown = shown
      message.text = target.slice(0, shown)
      // 揭示完毕 + 服务端已结束 → 收尾（此时才切成结构化渲染，光标/呼吸动画随之停止）
      if (!revealing && message.answerDone) message.streaming = false
    }
    if (revealing) {
      void scrollToLatestMessage('auto')
      typewriterFrame = requestAnimationFrame(tick)
    }
  }
  typewriterFrame = requestAnimationFrame(tick)
}
function ask() {
  const value = question.value
  const text = value.trim()
  if (!text || asking.value || loadingHistory.value) return
  const document = selectedDocument.value
  if (!backendEnabled.value || !document) {
    notify(sessionDocumentUnavailable.value
      ? '当前会话文档不可用，请重新新建会话并选择已解析文档'
      : '请先新建会话并选择一份已解析的文档')
    return
  }
  const session = chatSession.value
  chatMessages.value.push({ role: 'user', text })
  question.value = ''
  scrollToLatestMessage()
  asking.value = true
  const answerIndex = chatMessages.value.push({
    role: 'assistant', text: '正在检索知识库…', streaming: true,
  }) - 1
  const eventSource = new EventSource(chatStreamUrl(
    text, [document.kbId], chatSessionId.value, document.id,
  ))
  activeStream = eventSource
  let receivedToken = false
  let rawAnswer = ''
  eventSource.addEventListener('session', event => {
    if (session === chatSession.value) {
      const sessionId = (event as MessageEvent).data as string
      chatSessionId.value = sessionId
      saveChatDocumentId(sessionId, document.id)
    }
  })
  eventSource.addEventListener('status', event => {
    try {
      const payload = JSON.parse((event as MessageEvent).data) as { message?: string }
      if (session === chatSession.value && !receivedToken) {
        chatMessages.value[answerIndex].text = payload.message ?? '正在生成回答…'
      }
    } catch {
      chatMessages.value[answerIndex].text = '正在生成回答…'
    }
  })
  eventSource.addEventListener('token', event => {
    if (session !== chatSession.value) return
    receivedToken = true
    rawAnswer += (event as MessageEvent).data
    const target = chatMessages.value[answerIndex]
    // 不直接显示：先落到 rawText，由打字机按帧揭示
    target.rawText = cleanCitationMarkers(rawAnswer)
    if (target.shown === undefined) target.shown = 0
    pumpTypewriter()
  })
  eventSource.addEventListener('error', event => {
    if (event instanceof MessageEvent) {
      try {
        const payload = JSON.parse(event.data) as { message?: string }
        if (payload.message && session === chatSession.value) {
          chatMessages.value[answerIndex].text = payload.message
        }
      } catch {
        // 后端断开时显示统一错误信息
      }
    }
    if (session === chatSession.value) {
      if (!receivedToken && chatMessages.value[answerIndex].text !== '问答服务暂时不可用，请稍后重试。') {
        chatMessages.value[answerIndex].text = '问答服务暂时不可用，请稍后重试。'
      }
      // 清掉待揭示文本，否则打字机可能把错误提示覆盖掉
      chatMessages.value[answerIndex].rawText = undefined
      chatMessages.value[answerIndex].answerDone = true
      // 出错也要结束流式态，否则回答会永久停在纯文本渲染
      chatMessages.value[answerIndex].streaming = false
    }
    if (session === chatSession.value) asking.value = false
    eventSource.close()
    activeStream = null
  })
  eventSource.addEventListener('done', event => {
    if (session === chatSession.value) {
      const target = chatMessages.value[answerIndex]
      if (receivedToken) target.rawText = cleanCitationMarkers(rawAnswer)
      try {
        const result = JSON.parse((event as MessageEvent).data) as { sources?: Source[] }
        target.sources = result.sources ?? []
      } catch {
        // 无来源时保留模型回答
      }
      // 服务端结束：等打字机把剩下的字符揭示完，再切结构化渲染（避免文字突然跳变）
      target.answerDone = true
      pumpTypewriter()
      void loadChatSessions()
    }
    if (session === chatSession.value) asking.value = false
    eventSource.close()
    activeStream = null
  })
  scrollToLatestMessage()
}
function handleChatKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    ask()
  }
}
async function handleUpload(event: Event) {
  const input = event.target as HTMLInputElement
  const selected = Array.from(input.files ?? [])
  if (!selected.length) return
  const accepted = selected.filter(file => ['pdf', 'docx', 'md', 'txt'].includes(file.name.split('.').pop()?.toLowerCase() ?? ''))
  if (!accepted.length) {
    input.value = ''
    notify('请选择 PDF、DOCX、Markdown 或 TXT 文件（暂不支持 .doc）')
    return
  }
  if (backendEnabled.value) {
    try {
      if (!ragKnowledgeBaseId.value) {
        ragKnowledgeBaseId.value = (await createKnowledgeBase('农田建设政策资料')).id
      }
      for (const file of accepted) {
        await uploadDocument(ragKnowledgeBaseId.value, file)
      }
      await loadRagDocuments()
      showUpload.value = false
      notify(`已上传 ${accepted.length} 个文件，请点击“解析”开始解析`)
    } catch (error) {
      notify(error instanceof Error ? error.message : '上传失败')
    }
    input.value = ''
    return
  }
  input.value = ''
  notify('无法连接后端，文件未上传')
}
async function parseFile(file: PolicyFile) {
  const document = ragDocuments.value.find(item => item.id === file.id)
  if (!document || parsingRequests.value.has(file.id) || !['PENDING', 'FAILED'].includes(document.status)) return
  parsingRequests.value.add(file.id)
  try {
    await startDocumentParsing(document.kbId, document.id)
    document.status = 'PROCESSING'
    document.errorMsg = null
    startStatusPolling()
    notify('解析任务已提交')
  } catch (error) {
    notify(error instanceof Error ? error.message : '解析任务提交失败')
  } finally {
    parsingRequests.value.delete(file.id)
  }
}
async function downloadMock(file: PolicyFile) {
  const ragFile = ragDocuments.value.find(item => item.id === file.id)
  if (ragFile) {
    try {
      const blob = await downloadDocument(ragFile.kbId, file.id)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = file.name
      anchor.click()
      URL.revokeObjectURL(url)
      return
    } catch (error) {
      notify(error instanceof Error ? error.message : '下载失败')
      return
    }
  }
}
async function deleteFile(file: PolicyFile) {
  const document = ragDocuments.value.find(item => item.id === file.id)
  if (document) {
    try {
      await deleteDocument(document.kbId, file.id)
      ragDocuments.value = ragDocuments.value.filter(item => item.id !== file.id)
      notify('文档已删除')
    } catch (error) {
      notify(error instanceof Error ? error.message : '删除失败')
    }
  }
}
async function askAboutFile(file: PolicyFile) {
  const document = ragDocuments.value.find(item => item.id === file.id)
  if (!document || document.status !== 'DONE') {
    notify('文档尚未解析完成，暂不能基于此文件提问')
    return
  }
  await router.push('/')
  await startNewChat(document)
}
</script>

<template>
  <div class="app-shell" :class="{ 'app-shell-home': activePage === 'home' }">
    <header class="topbar">
      <button class="brand" aria-label="农田 AI 助手首页" @click="navigate('/')">
        <span class="brand-mark"><Sprout :size="19" :stroke-width="2.4" /></span>
        <span>农田<span class="brand-ai">AI</span>助手</span>
      </button>
      <nav class="main-nav" aria-label="主导航">
        <button :class="{ active: activePage === 'home' }" @click="navigate('/')"><span>首页</span></button>
        <button :class="{ active: activePage === 'knowledge' }" @click="navigate('/knowledge')"><BookOpen :size="15" /><span>知识图谱</span></button>
        <button :class="{ active: activePage === 'documents' }" @click="navigate('/documents')"><FileText :size="15" /><span>政策文件</span></button>
        <button :class="{ active: activePage === 'review' }" @click="navigate('/review')"><FileCheck2 :size="15" /><span>文档审核</span></button>
      </nav>
      <div class="topbar-right">
        <button
          class="theme-toggle"
          type="button"
          :aria-pressed="whiteMode"
          :title="whiteMode ? '切换为深色' : '切换为白色'"
          @click="toggleWhiteMode"
        >
          <Sun v-if="whiteMode" :size="14" />
          <Moon v-else :size="14" />
          <span>{{ whiteMode ? '白色' : '深色' }}</span>
        </button>
        <span class="status-dot"></span>
        <span>演示空间</span>
        <button class="avatar" aria-label="个人中心">林</button>
      </div>
      <button class="mobile-menu" @click="navigate(activePage === 'documents' ? '/' : '/documents')"><MoreHorizontal /></button>
    </header>

    <Transition name="page-fade">
    <main v-if="activePage === 'home'" key="home" class="home-page home-workspace">
      <aside class="workspace-sidebar" :class="{ 'is-mobile-open': showMobileHistory }" aria-label="问答快捷导航">
        <button class="new-chat-button" @click="openDocumentPicker"><Plus :size="17" />新建会话</button>
        <div class="sidebar-section-title">历史对话</div>
        <div class="chat-session-list">
          <button
            v-for="session in chatSessions"
            :key="session.id"
            class="current-chat-link"
            :class="{ 'is-active': chatSessionId === session.id }"
            :title="session.title || '未命名对话'"
            :aria-current="chatSessionId === session.id ? 'true' : undefined"
            @click="openChatSession(session.id)"
          >
            <MessageCircle :size="16" /><span>{{ session.title || '未命名对话' }}</span>
          </button>
          <p v-if="!chatSessions.length" class="sidebar-empty">还没有历史对话，选择文档后开始提问。</p>
        </div>
        <div class="sidebar-divider"></div>
        <div class="sidebar-section-title">快捷入口</div>
        <button class="sidebar-link" @click="navigate('/documents')"><FolderOpen :size="16" />浏览政策文件<ArrowRight :size="14" /></button>
        <button class="sidebar-link" @click="openUploadFromHome"><Upload :size="16" />上传文档<ArrowRight :size="14" /></button>
        <div class="sidebar-tip"><ShieldCheck :size="16" /><span>{{ backendEnabled ? '问答基于已解析文档生成。' : '后端未连接，暂不能提问或上传。' }}</span></div>
      </aside>

      <section class="workspace-main">
        <div class="workspace-header">
          <div><strong>智能问答</strong><span>基于政策资料的农田建设助手</span></div>
          <div class="workspace-header-actions">
            <button class="header-history-button" :aria-expanded="showMobileHistory" aria-label="历史对话" @click="showMobileHistory = !showMobileHistory"><MessageCircle :size="16" />历史对话</button>
            <button class="header-new-chat" @click="openDocumentPicker"><Plus :size="16" />新建会话</button>
            <button @click="navigate('/documents')"><FolderOpen :size="16" />政策文件<ArrowRight :size="14" /></button>
          </div>
        </div>
        <div ref="messageArea" class="workspace-content" :class="{ 'has-messages': chatMessages.length > 0 || !!selectedDocument }">
          <div v-if="loadingHistory" class="chat-loading">正在加载历史对话…</div>
          <div v-else-if="!chatMessages.length && !selectedDocument && !chatSessionId" class="welcome-panel">
            <div class="welcome-mark"><Sprout :size="28" :stroke-width="1.9" /></div>
            <div class="welcome-kicker">农田 AI 助手 · 智能问答</div>
            <h1>从一个问题开始，<span>找到建设的依据。</span></h1>
            <p>围绕农田建设、项目管理和政策规范，为你梳理清晰的思路。</p>
            <section class="home-prompts" aria-label="热门问题与热门标签">
              <div class="home-prompts-title"><Sparkles :size="15" />大家都在问<em>· 热门问题</em></div>
              <div class="home-hot-grid">
                <button
                  v-for="(item, index) in hotQuestions"
                  :key="item"
                  type="button"
                  class="home-hot-item"
                  :class="{ 'is-active': activeHotQuestion === item }"
                  :aria-pressed="activeHotQuestion === item"
                  @click="toggleHotQuestion(item)"
                >
                  <span class="home-hot-index">{{ hotIndex(index) }}</span>
                  <span class="home-hot-text">{{ item }}</span>
                  <ArrowRight :size="15" aria-hidden="true" />
                </button>
              </div>
              <div class="home-tags">
                <span class="home-tags-label">热门标签</span>
                <button
                  v-for="tag in hotTags"
                  :key="tag"
                  type="button"
                  class="home-tag"
                  :class="{ 'is-active': activeHotTag === tag }"
                  :aria-pressed="activeHotTag === tag"
                  @click="toggleHotTag(tag)"
                >{{ tag }}</button>
              </div>
            </section>
          </div>
          <div v-else class="conversation-thread">
            <div class="thread-title"><span class="live-dot"></span>当前对话 <span v-if="selectedDocument" :title="selectedDocument.fileName">· 《{{ selectedDocument.fileName }}》</span><span v-else-if="sessionDocumentUnavailable">· 当前会话文档不可用，请重新新建会话并选择已解析文档</span><span v-else>· 未绑定文档，仅可查看历史</span></div>
            <div v-if="selectedDocument" class="conversation-message assistant">
              <span class="conversation-avatar"><Sparkles :size="17" /></span>
              <div class="conversation-content">
                <span class="conversation-author">农田 AI 助手</span>
                <p>{{ selectionGreeting }}</p>
              </div>
            </div>
            <div v-for="(message, index) in chatMessages" :key="index" :class="['conversation-message', message.role, { 'is-streaming': message.streaming }]">
              <span class="conversation-avatar"><Sparkles v-if="message.role === 'assistant'" :size="17" /><span v-else>我</span></span>
              <div class="conversation-content">
                <span v-if="message.role === 'assistant'" class="conversation-author">农田 AI 助手</span>
                <span v-if="message.role === 'assistant' && message.streaming" class="conversation-status" aria-hidden="true"><i></i><i></i><i></i></span>
                <MessageBody v-if="message.role === 'assistant'" :text="message.text" :streaming="message.streaming" />
                <p v-else>{{ message.text }}</p>
                <SourceCards v-if="message.sources?.length" :sources="message.sources" />
              </div>
            </div>
          </div>
        </div>
        <div class="composer-wrap">
          <div class="chat-composer">
            <label class="sr-only" for="chat-question">向农田 AI 助手提问</label>
            <textarea id="chat-question" ref="questionInput" v-model="question" rows="2" :disabled="!selectedDocument || loadingHistory" :placeholder="selectedDocument ? `向《${selectedDocument.fileName}》提问 · Enter 发送` : sessionDocumentUnavailable ? '当前会话文档不可用，请重新新建会话并选择已解析文档' : '请先新建会话并选择已解析文档'" @keydown="handleChatKeydown"></textarea>
            <div class="composer-footer"><span><ShieldCheck :size="14" />AI 生成内容仅供参考，正式业务请以文件为准</span><button :disabled="!selectedDocument || !question.trim() || asking || loadingHistory" @click="ask()">发送<ArrowUp :size="16" /></button></div>
          </div>
          <span class="composer-note">{{ selectedDocument ? `仅检索《${selectedDocument.fileName}》` : sessionDocumentUnavailable ? '当前会话文档不可用，请重新新建会话并选择已解析文档' : '先选择已解析文档，才能开始提问' }}</span>
        </div>
      </section>
    </main>

    <main v-else-if="activePage === 'documents'" key="documents" class="documents-page">
      <div class="breadcrumb"><button @click="navigate('/')">首页</button><span>/</span><span>政策文件</span></div>
      <section class="page-heading">
        <div class="page-title-wrap"><div class="page-icon"><FileText :size="22" /></div><div><div class="title-line"><h1>政策文件</h1><span class="count-pill">{{ allFiles.length }} 份资料</span></div><p>查阅农田建设政策、技术规范与设计参考资料</p></div></div>
        <div class="heading-actions"><button class="outline-button" @click="notify('原型演示：文件夹管理暂未接入')"><FolderOpen :size="16" />文件分类</button><button class="primary-button" @click="showUpload = true"><Upload :size="16" />上传文件</button></div>
      </section>
      <section class="library-toolbar">
        <label class="library-search"><Search :size="18" /><input ref="librarySearchInput" v-model="searchText" placeholder="搜索文件名称、标准编号或标签…" /><kbd>Ctrl K</kbd></label>
        <div class="toolbar-right">
          <button class="filter-button" @click="selectedFilter = selectedFilter === '全部文件' ? '标准规范' : selectedFilter === '标准规范' ? '政策文件' : '全部文件'"><Tags :size="15" />{{ selectedFilter }}<ChevronDown :size="15" /></button>
          <label class="sort-select"><span>排序</span><select v-model="sortOrder"><option>最新上传</option><option>文件名 A-Z</option><option>文件名 Z-A</option></select><ChevronDown :size="14" /></label>
        </div>
      </section>
      <div class="library-summary"><span>全部资料 <strong>{{ visibleFiles.length }}</strong></span><span class="summary-divider"></span><span><i class="mini-status"></i>已整理标准规范 <strong>{{ standardFileCount }}</strong></span><span class="summary-spacer"></span><button :class="['view-toggle', { active: fileView === 'list' }]" aria-label="列表视图" :aria-pressed="fileView === 'list'" @click="fileView = 'list'"><span></span><span></span><span></span></button><button :class="['view-toggle', 'grid-toggle', { active: fileView === 'grid' }]" aria-label="网格视图" :aria-pressed="fileView === 'grid'" @click="fileView = 'grid'"><span v-for="i in 4" :key="i"></span></button></div>
      <section :class="['file-list', { 'file-grid': fileView === 'grid' }]">
        <article v-for="file in visibleFiles" :key="file.id" class="file-card">
          <div :class="['file-icon', file.color]"><FileText :size="22" /><span>{{ file.name.toLowerCase().endsWith('.pdf') ? 'PDF' : 'DOC' }}</span></div>
          <div class="file-info">
            <button class="file-name" :disabled="file.status !== 'DONE'" @click="askAboutFile(file)">{{ file.name }}</button>
            <div class="file-meta"><span class="file-type">{{ file.type }}</span><span><Clock3 :size="13" />{{ file.date }}</span><span class="meta-dot"></span><span>{{ file.size }}</span><span v-if="file.pages" class="pages-label">{{ file.pages }} 页</span></div>
            <div class="file-tags"><span v-for="tag in file.tags" :key="tag" :class="{ 'failed-tag': file.status === 'FAILED' }" :title="file.errorMsg ?? ''">{{ tag }}</span><span v-if="file.status === 'FAILED'">可点击重新解析</span></div>
          </div>
          <div class="file-actions">
            <button class="parse-action" :aria-label="file.status === 'FAILED' ? '重新解析' : '解析文档'" :title="file.status === 'FAILED' ? '重新解析' : '解析文档'" :disabled="file.status === 'DONE' || file.status === 'PROCESSING' || parsingRequests.has(file.id)" @click="parseFile(file)"><LoaderCircle v-if="file.status === 'PROCESSING' || parsingRequests.has(file.id)" class="parse-spinner" :size="16" /><FileCheck2 v-else :size="16" /><span>{{ file.status === 'PROCESSING' || parsingRequests.has(file.id) ? '解析中' : file.status === 'FAILED' ? '重新解析' : file.status === 'DONE' ? '已解析' : '解析' }}</span></button>
            <button aria-label="下载" title="下载原文件" @click="downloadMock(file)"><ArrowDown :size="16" /></button>
            <button aria-label="选择此文档开始会话" title="选择此文档开始会话" :disabled="ragDocuments.find(doc => doc.id === file.id)?.status !== 'DONE'" @click="askAboutFile(file)"><MessageCircle :size="16" /></button>
            <button aria-label="删除" title="删除文件" class="delete-action" @click="deleteFile(file)"><X :size="16" /></button>
          </div>
          <button class="mobile-file-arrow" aria-label="选择此文档开始会话" :disabled="file.status !== 'DONE'" @click="askAboutFile(file)"><ArrowRight :size="17" /></button>
        </article>
        <div v-if="!visibleFiles.length" class="empty-state"><Search :size="26" /><strong>{{ !backendEnabled ? '后端尚未连接' : allFiles.length ? '没有找到匹配的文件' : '还没有上传文件' }}</strong><span>{{ !backendEnabled ? '请启动后端并配置数据库后刷新页面' : allFiles.length ? '试试其他名称或清除筛选' : '上传第一份资料后会显示在这里' }}</span><button v-if="allFiles.length" @click="searchText = ''; selectedFilter = '全部文件'">清除筛选</button></div>
      </section>
      <div class="library-bottom"><span>已显示 {{ visibleFiles.length }} 份资料</span><span>{{ backendEnabled ? '资料来自知识库' : '等待后端连接' }}</span></div>
      <div class="prototype-note"><ShieldCheck :size="15" /><span>{{ backendEnabled ? '文件来自知识库，解析完成后支持问答。' : '当前后端未连接；不会模拟上传结果。' }}</span></div>
    </main>

    <KnowledgeGraph v-else-if="activePage === 'knowledge'" key="knowledge" />
    <DocumentReview v-else key="review" />
    </Transition>

    <footer v-if="activePage !== 'home'" class="site-footer"><span>© 2026 农田 AI 助手</span><span class="footer-separator"></span><span>让农业知识服务更简单</span></footer>
  </div>

  <div v-if="showDocumentPicker" class="modal-backdrop" @click.self="showDocumentPicker = false">
      <div class="document-picker-modal" role="dialog" aria-modal="true" aria-labelledby="document-picker-title">
        <button class="modal-close" type="button" aria-label="关闭文档选择" @click="showDocumentPicker = false"><X :size="18" /></button>
        <div class="document-picker-heading">
          <div class="document-picker-icon"><FolderOpen :size="21" /></div>
          <div>
            <h2 id="document-picker-title">选择要提问的文档</h2>
            <p>仅显示已解析完成的文档；这段会话将只检索所选文档。</p>
          </div>
        </div>
        <div v-if="loadingDocumentPicker" class="document-picker-state">正在加载已解析文档…</div>
        <div v-else-if="!parsedDocuments.length" class="document-picker-state">
          <FileText :size="24" />
          <span>{{ backendEnabled ? '暂无已解析文档，请先上传并解析文件。' : '暂时无法获取文档，请确认后端连接后重试。' }}</span>
          <button v-if="backendEnabled" type="button" @click="showDocumentPicker = false; navigate('/documents')">查看政策文件<ArrowRight :size="14" /></button>
        </div>
        <div v-else class="document-picker-list" aria-label="已解析文档">
          <button
            v-for="document in parsedDocuments"
            :key="document.id"
            type="button"
            class="document-picker-item"
            :aria-pressed="pendingDocumentId === document.id"
            :class="{ 'is-selected': pendingDocumentId === document.id }"
            @click="pendingDocumentId = document.id"
          >
            <span class="document-picker-file-icon"><FileText :size="18" /></span>
            <span class="document-picker-name" :title="document.fileName">{{ document.fileName }}</span>
            <span class="document-picker-check"><Check v-if="pendingDocumentId === document.id" :size="14" /></span>
          </button>
        </div>
        <div class="document-picker-footer">
          <span v-if="parsedDocuments.length && !loadingDocumentPicker">已解析 {{ parsedDocuments.length }} 份 · 请选择一份</span>
          <span v-else>选择后再进入会话</span>
          <button type="button" :disabled="pendingDocumentId === null || loadingDocumentPicker" @click="confirmDocumentSelection">进入对话<ArrowRight :size="15" /></button>
        </div>
      </div>
    </div>
    <div v-if="showUpload" class="modal-backdrop" @click.self="showUpload = false">
      <div class="upload-modal">
        <button class="modal-close" aria-label="关闭" @click="showUpload = false"><X :size="18" /></button>
        <div class="modal-icon"><CloudUpload :size="23" /></div>
        <h2>上传政策文件</h2><p>将文件加入资料列表，后续可用于知识检索与问答。</p>
        <label class="drop-zone"><input type="file" accept=".pdf,.docx,.md,.txt" multiple @change="handleUpload" /><span class="drop-icon"><FilePlus2 :size="21" /></span><strong>点击选择文件</strong><span>支持 PDF、DOCX、MD、TXT；暂不支持旧版 .doc</span></label>
        <div class="upload-tip"><Check :size="14" />{{ backendEnabled ? '上传后不会自动解析，请在文件列表点击“解析”' : '后端未连接，暂无法上传' }}</div>
      </div>
    </div>
  <!-- 弹窗与提示刻意放在 .app-shell 之外：白色主题对整个 .app-shell 做色调翻转，
       而 filter 会把该祖先变成 position:fixed 的包含块——留在里面弹窗就不再固定于视口、会跟着页面滚 -->
  <Transition name="toast"><div v-if="toast" class="toast-message"><Check :size="16" />{{ toast }}</div></Transition>
</template>
