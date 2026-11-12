<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import KnowledgeGraph from './views/KnowledgeGraph.vue'
import DocumentReview from './views/DocumentReview.vue'
import MessageBody from './components/MessageBody.vue'
import SourceCards from './components/SourceCards.vue'
import VoiceInput from './components/VoiceInput.vue'
import {
  chatStreamUrl, createKnowledgeBase, deleteChatSession, deleteDocument, downloadDocument,
  getChatMessages, getDocumentStatus, listChatSessions, listHotQuestions,
  listDocuments, listKnowledgeBases, renameChatSession, startDocumentParsing, uploadDocument,
  type ChatMessage as SavedChatMessage,
  type ChatSession as SavedChatSession,
  type Document as RagDocument,
  type KnowledgeBase,
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
const knowledgeBases = ref<KnowledgeBase[]>([])
const backendEnabled = ref(false)
const chatSessionId = ref<string | null>(null)
const chatSessions = ref<SavedChatSession[]>([])
const loadingHistory = ref(false)
const showMobileHistory = ref(false)
/** 历史对话行的更多操作：当前展开菜单的会话 id */
const menuSessionId = ref<string | null>(null)
/** 删除前的行内二次确认：待确认删除的会话 id */
const deleteTargetId = ref<string | null>(null)
/** 行内重命名：正在编辑的会话 id 与草稿标题 */
const editingSessionId = ref<string | null>(null)
const editingTitle = ref('')
const selectedDocumentId = ref<number | null>(null)
/** 当前会话在服务端绑定的文档；为 null 表示这个会话固定在"整个知识库"范围 */
const sessionDocId = ref<number | null>(null)

/**
 * 默认知识库：不主动指定文件时，整个会话都在这个库里检索。
 * 后端的 docId 为空 = 检索整个知识库，所以答案的来源可以同时来自库里多份文档。
 * 库里找不到这个 id 时退回列表第一个（当前库里只有一个知识库，实际用的就是它）。
 */
const DEFAULT_KNOWLEDGE_BASE_ID = 2
const asking = ref(false)
const speechBusy = ref(false)
const voiceInput = ref<InstanceType<typeof VoiceInput> | null>(null)
const parsingRequests = ref(new Set<number>())
let activeStream: EventSource | null = null
let typewriterFrame = 0
let statusTimer: number | undefined
let toastTimer: number | undefined

const allFiles = computed(() => ragDocuments.value.map(toPolicyFile))
const parsedDocuments = computed(() => ragDocuments.value.filter(document => document.status === 'DONE'))
const selectedDocument = computed(() => parsedDocuments.value.find(document => document.id === selectedDocumentId.value) ?? null)
/** 默认知识库：配置的 id 优先，找不到就用列表第一个 */
const defaultKnowledgeBase = computed(() => knowledgeBases.value.find(base => base.id === DEFAULT_KNOWLEDGE_BASE_ID)
  ?? knowledgeBases.value[0]
  ?? null)
/** 当前提问用的知识库：选了具体文件就跟文件走，否则用默认知识库 */
const activeKnowledgeBaseId = computed(() => selectedDocument.value?.kbId
  ?? defaultKnowledgeBase.value?.id
  ?? ragKnowledgeBaseId.value)
const activeKnowledgeBaseName = computed(() => defaultKnowledgeBase.value?.name ?? '')
const currentSessionTitle = computed(() => {
  const session = chatSessionId.value ? findSession(chatSessionId.value) : null
  if (session?.title?.trim()) return session.title.trim()
  const firstQuestion = chatMessages.value.find(message => message.role === 'user')?.text?.trim()
  if (firstQuestion) return firstQuestion
  if (selectedDocument.value) return selectedDocument.value.fileName
  return activeKnowledgeBaseName.value || '新建会话'
})
/** 只有"会话确实绑了文档、但那份文档已不可用"才算异常；整库会话不算 */
const sessionDocumentUnavailable = computed(() => Boolean(
  chatSessionId.value && sessionDocId.value !== null && !selectedDocument.value,
))
const selectionGreeting = computed(() => {
  if (selectedDocument.value) return `你当前选择的是《${selectedDocument.value.fileName}》。你想问什么？`
  if (activeKnowledgeBaseName.value) return `当前检索范围是《${activeKnowledgeBaseName.value}》里的全部资料。你想问什么？`
  return ''
})
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

/**
 * 会话绑定的文档。
 * docId 为空 = 这个会话就是"整个知识库"范围（默认），不能再退回某一份文档，
 * 否则界面会显示成"只检索某一份"，与后端的实际范围不一致。
 */
function resolveSessionDocument(session: SavedChatSession) {
  if (typeof session.docId !== 'number') return null
  const documentsInScope = parsedDocuments.value.filter(document => session.kbIds.includes(document.kbId))
  return documentsInScope.find(document => document.id === session.docId) ?? null
}

async function openChatSession(sessionId: string) {
  voiceInput.value?.cancel()
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
    sessionDocId.value = session && typeof session.docId === 'number' ? session.docId : null
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

/** 历史对话的操作菜单：锚点样式（fixed 浮层，靠右对齐到「更多」按钮） */
const menuStyle = ref<Record<string, string>>({})
/** 菜单归属的会话（菜单被 teleport 到 body，渲染在 v-for 之外，这里按 id 取回） */
const menuSession = computed(() => chatSessions.value.find(session => session.id === menuSessionId.value) ?? null)
/** 菜单高度估算（两项 26px + 内边距 + 边框），用于判断是否需要向上翻转 */
const SESSION_MENU_HEIGHT = 70

/* ── 历史对话行的更多操作：重命名 / 删除（左侧列表） ───────────── */

/** 展开/收起某一行的操作菜单（同时只开一个） */
function toggleSessionMenu(sessionId: string, event: MouseEvent) {
  if (menuSessionId.value === sessionId) {
    closeSessionMenu()
    return
  }
  const anchor = event.currentTarget as HTMLElement | null
  const rect = anchor?.getBoundingClientRect()
  const viewportHeight = window.innerHeight
  // 下方放不下就翻到按钮上方，避免浮层超出视口
  const fitsBelow = rect ? viewportHeight - rect.bottom >= SESSION_MENU_HEIGHT : true
  const top = fitsBelow
    ? (rect ? rect.bottom + 4 : 8)
    : Math.max(8, (rect ? rect.top : 0) - SESSION_MENU_HEIGHT - 4)
  menuStyle.value = { top: `${top}px`, left: `${rect ? rect.right : 0}px` }
  menuSessionId.value = sessionId
  deleteTargetId.value = null
}

function closeSessionMenu() {
  menuSessionId.value = null
  deleteTargetId.value = null
}

/** 点页面空白处收起菜单（菜单与行内按钮都做了 @click.stop，不会误触） */
function handleDocumentClick() {
  if (menuSessionId.value) closeSessionMenu()
}

/** 列表滚动 / 窗口尺寸变化时收起菜单：浮层是固定定位，锚点一动就对不齐了 */
function handleMenuAnchorLost() {
  if (menuSessionId.value) closeSessionMenu()
}

/** 进入行内重命名：标题换成输入框并选中全文 */
async function startRename(session: SavedChatSession) {
  closeSessionMenu()
  editingSessionId.value = session.id
  editingTitle.value = session.title ?? ''
  await nextTick()
  // v-for 里的 template ref 在 Vue 3 里会变成数组，这里直接查（同一时刻只可能有一个输入框）
  const input = document.querySelector<HTMLInputElement>('.session-rename-input')
  input?.focus()
  input?.select()
}

function cancelRename() {
  editingSessionId.value = null
  editingTitle.value = ''
}

/**
 * 提交重命名：Enter 与失焦都会触发，所以先把编辑态收掉，保证只提交一次；
 * 标题为空或没改动就直接退出，不打扰后端。
 */
async function confirmRename(session: SavedChatSession) {
  if (editingSessionId.value !== session.id) return
  const title = editingTitle.value.trim()
  const original = (session.title ?? '').trim()
  editingSessionId.value = null
  editingTitle.value = ''
  if (!title || title === original) return
  try {
    const updated = await renameChatSession(session.id, title)
    const target = findSession(session.id)
    if (target) target.title = updated.title ?? title
    notify('已重命名')
  } catch (error) {
    notify(error instanceof Error ? error.message : '重命名失败')
  }
}

function askDelete(sessionId: string) {
  deleteTargetId.value = sessionId
}

/** 确认删除：后端软删除 → 本地列表移除；删的是当前会话就回到空白的新会话 */
async function confirmDelete(session: SavedChatSession) {
  if (asking.value) {
    notify('请等待当前回答完成后再删除会话')
    return
  }
  try {
    await deleteChatSession(session.id)
    chatSessions.value = chatSessions.value.filter(item => item.id !== session.id)
    if (chatSessionId.value === session.id) {
      await startNewChat(null)
    }
    closeSessionMenu()
    notify('会话已删除')
  } catch (error) {
    notify(error instanceof Error ? error.message : '删除失败')
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
    const bases = await listKnowledgeBases()
    knowledgeBases.value = bases
    ragKnowledgeBaseId.value = (bases.find(base => base.id === DEFAULT_KNOWLEDGE_BASE_ID) ?? bases[0])?.id ?? null
    ragDocuments.value = bases.length
      ? (await Promise.all(bases.map(base => listDocuments(base.id)))).flat()
      : []
    ragDocuments.value.sort((a, b) => Date.parse(b.uploadedAt) - Date.parse(a.uploadedAt))
    backendEnabled.value = true
    if (ragDocuments.value.some(doc => doc.status === 'PROCESSING')) {
      startStatusPolling()
    }
  } catch (error) {
    backendEnabled.value = false
    ragDocuments.value = []
    knowledgeBases.value = []
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
  voiceInput.value?.cancel()
  showUpload.value = route.path === '/documents' && route.query.upload === '1'
  showMobileHistory.value = false
  // 会话操作菜单是 teleport 到 body 的浮层，路由一换必须收起（否则会飘在新页面上）
  if (menuSessionId.value) closeSessionMenu()
  if (route.path === '/documents') {
    searchText.value = typeof route.query.tag === 'string' ? route.query.tag : ''
  }
})

onMounted(() => {
  restoreWhiteMode()
  void loadRagDocuments()
  void loadChatSessions()
  void loadHotQuestions()
})

function handlePageKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    showUpload.value = false
    if (menuSessionId.value) closeSessionMenu()
    if (editingSessionId.value) cancelRename()
  }
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k' && activePage.value === 'documents') {
    event.preventDefault()
    librarySearchInput.value?.focus()
  }
}

onMounted(() => window.addEventListener('keydown', handlePageKeydown))
onUnmounted(() => window.removeEventListener('keydown', handlePageKeydown))
// 历史对话的操作菜单：点页面其他位置、或列表滚动/窗口变尺寸时收起
// （scroll 不冒泡，用捕获阶段在 window 上监听才能拿到列表内的滚动）
onMounted(() => document.addEventListener('click', handleDocumentClick))
onUnmounted(() => document.removeEventListener('click', handleDocumentClick))
onMounted(() => window.addEventListener('scroll', handleMenuAnchorLost, true))
onUnmounted(() => window.removeEventListener('scroll', handleMenuAnchorLost, true))
onMounted(() => window.addEventListener('resize', handleMenuAnchorLost))
onUnmounted(() => window.removeEventListener('resize', handleMenuAnchorLost))
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

/**
 * 首页热门问题从后端读取，与答案缓存及预热共用同一份名单。
 * 标签仅用于展示和筛选，不参与答案缓存。
 */
const hotQuestions = ref<string[]>([])

async function loadHotQuestions() {
  try {
    hotQuestions.value = await listHotQuestions()
  } catch (error) {
    hotQuestions.value = []
    notify(error instanceof Error ? error.message : '热门问题加载失败')
  }
}
const hotTags = [
  '高标准农田', '农田建设', '质量验收', '设计参考', '项目管理', '技术标准', '政策文件',
]
const activeHotQuestion = ref<string | null>(null)

/**
 * 点击热门问题：**直接发起提问**（进对话页，不再只是高亮）。
 * 独立首轮提问可读取热门答案缓存；已有历史、指定文件或追问时正常检索生成；
 * 对话照常落库，历史里能看到这次问答。
 */
async function askHotQuestion(item: string) {
  if (asking.value || loadingHistory.value || speechBusy.value) {
    notify('请等当前回答结束后再试')
    return
  }
  voiceInput.value?.commit()
  activeHotQuestion.value = item
  question.value = item
  await nextTick()
  ask()
}
/** 热门问题序号：1 → "01" */
function hotIndex(index: number) {
  return String(index + 1).padStart(2, '0')
}

/**
 * 历史对话标题固定显示前 N 个字：短的照原样（不补空格），长的直接截断（不加省略号）。
 * 用字符数而不是 CSS 裁切，是因为按像素裁会把最后一个字切成半个。
 * 10 是按侧栏文字区宽度定的：256px 侧栏减去内边距/图标/「更多」按钮后约 170px，
 * 14.5px 字号下 10 个汉字 ≈ 145px，留足余量；取 12 就会顶到边缘被再切一次。
 */
const SESSION_TITLE_CHARS = 10
function shortSessionTitle(title: string | null) {
  const text = (title ?? '').trim() || '未命名对话'
  return Array.from(text).slice(0, SESSION_TITLE_CHARS).join('')
}

/** 主题切换：首次访问默认白色；只有用户明确选过深色才用深色。 */
const whiteMode = ref(document.documentElement.dataset.theme !== 'dark')
function applyWhiteMode(value: boolean) {
  whiteMode.value = value
  document.documentElement.dataset.theme = value ? 'white' : 'dark'
  document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
    ?.setAttribute('content', value ? '#f6f8fa' : '#08111b')
  try {
    window.localStorage.setItem('hbnt-theme-v2', value ? 'white' : 'dark')
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
    saved = window.localStorage.getItem('hbnt-theme-v2')
  } catch {
    saved = null
  }
  // 默认白色：只有明确存过 'dark' 才用深色
  whiteMode.value = saved !== 'dark'
  document.documentElement.dataset.theme = whiteMode.value ? 'white' : 'dark'
  document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
    ?.setAttribute('content', whiteMode.value ? '#f6f8fa' : '#08111b')
}
/** 新建会话：不绑定具体文件，默认检索整个默认知识库（答案可来自库里多份文档） */
async function startDefaultChat() {
  await startNewChat(null)
  if (backendEnabled.value && !defaultKnowledgeBase.value) {
    notify('暂未配置知识库，仍可闲聊或提出业务查询；文档问答需先上传并解析资料')
  }
}
async function startNewChat(document: RagDocument | null) {
  voiceInput.value?.cancel()
  activeStream?.close()
  activeStream = null
  asking.value = false
  chatSessionId.value = null
  sessionDocId.value = null
  chatSession.value += 1
  loadingHistory.value = false
  selectedDocumentId.value = document?.id ?? null
  chatMessages.value = []
  question.value = ''
  showMobileHistory.value = false
  await nextTick()
  messageArea.value?.scrollTo({ top: 0, behavior: 'instant' })
  questionInput.value?.focus()
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
  if (!text || asking.value || loadingHistory.value || speechBusy.value) return
  const document = selectedDocument.value
  const kbId = activeKnowledgeBaseId.value
  const session = chatSession.value
  voiceInput.value?.commit()
  chatMessages.value.push({ role: 'user', text })
  question.value = ''
  scrollToLatestMessage()
  asking.value = true
  const answerIndex = chatMessages.value.push({
    role: 'assistant', text: '正在理解问题…', streaming: true,
  }) - 1
  // docId 为空 = 检索整个知识库，答案来源可以同时来自库里多份文档
  const eventSource = new EventSource(chatStreamUrl(
    text, kbId === null ? [] : [kbId], chatSessionId.value, document?.id ?? null,
  ))
  activeStream = eventSource
  let receivedToken = false
  let rawAnswer = ''
  eventSource.addEventListener('session', event => {
    if (session === chatSession.value) {
      const sessionId = (event as MessageEvent).data as string
      chatSessionId.value = sessionId
    }
  })
  // 只有后端确定进入 RAG 并验证成功，才把页面选择视为本会话已绑定的范围。
  eventSource.addEventListener('scope', event => {
    if (session !== chatSession.value) return
    try {
      const scope = JSON.parse((event as MessageEvent).data) as { kbIds: number[]; docId: number | null }
      sessionDocId.value = typeof scope.docId === 'number' ? scope.docId : null
      if (chatSessionId.value && sessionDocId.value !== null) {
        saveChatDocumentId(chatSessionId.value, sessionDocId.value)
      }
    } catch {
      // 范围仅由服务端确认，不用无效事件覆盖已有选择。
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
    let errorMessage = '问答服务暂时不可用，请稍后重试。'
    if (event instanceof MessageEvent) {
      try {
        const payload = JSON.parse(event.data) as { message?: string }
        if (payload.message) errorMessage = payload.message
      } catch {
        // 后端断开时显示统一错误信息
      }
    }
    if (session === chatSession.value) {
      chatMessages.value[answerIndex].text = receivedToken
        ? `${cleanCitationMarkers(rawAnswer)}\n\n（回答中断：${errorMessage}）` : errorMessage
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
      </div>
      <button class="mobile-menu" @click="navigate(activePage === 'documents' ? '/' : '/documents')"><MoreHorizontal /></button>
    </header>

    <Transition name="page-fade">
    <main v-if="activePage === 'home'" key="home" class="home-page home-workspace">
      <aside class="workspace-sidebar" :class="{ 'is-mobile-open': showMobileHistory }" aria-label="问答快捷导航">
        <button class="new-chat-button" @click="startDefaultChat"><Plus :size="17" />新建会话</button>
        <div class="sidebar-section-title">历史对话</div>
        <div class="chat-session-list">
          <div
            v-for="session in chatSessions"
            :key="session.id"
            class="chat-session-row"
            :class="{ 'is-active': chatSessionId === session.id, 'is-menu-open': menuSessionId === session.id }"
          >
            <!-- 行内重命名：标题变成输入框，Enter 保存 / Esc 取消 / 失焦保存 -->
            <input
              v-if="editingSessionId === session.id"
              v-model="editingTitle"
              class="session-rename-input"
              maxlength="200"
              aria-label="重命名会话"
              @click.stop
              @keydown.enter.prevent="confirmRename(session)"
              @keydown.esc.prevent="cancelRename"
              @blur="confirmRename(session)"
            />
            <template v-else>
              <button
                class="current-chat-link"
                :class="{ 'is-active': chatSessionId === session.id }"
                :aria-current="chatSessionId === session.id ? 'true' : undefined"
                :aria-label="session.title || '未命名对话'"
                @click="openChatSession(session.id)"
              >
                <MessageCircle :size="16" /><span>{{ shortSessionTitle(session.title) }}</span>
              </button>
              <!-- 悬浮（或当前会话）时出现在行右侧、垂直居中；不放 title：避免浏览器黑框提示 -->
              <button
                class="chat-session-more"
                type="button"
                :aria-label="`「${session.title || '未命名对话'}」的更多操作`"
                :aria-expanded="menuSessionId === session.id ? 'true' : 'false'"
                @click.stop="toggleSessionMenu(session.id, $event)"
              ><MoreHorizontal :size="15" /></button>
            </template>
          </div>
          <p v-if="!chatSessions.length" class="sidebar-empty">还没有历史对话，选择文档后开始提问。</p>
        </div>
        <div class="sidebar-tip"><ShieldCheck :size="16" /><span>{{ backendEnabled ? '政策问题答案基于已解析文档生成。' : '后端未连接，暂不能提问或上传。' }}</span></div>
      </aside>

      <section class="workspace-main">
        <div class="workspace-header">
          <div><strong>智能问答</strong><span>基于政策资料的农田建设助手</span></div>
          <div class="workspace-header-actions">
            <button class="header-history-button" :aria-expanded="showMobileHistory" aria-label="历史对话" @click="showMobileHistory = !showMobileHistory"><MessageCircle :size="16" />历史对话</button>
            <button class="header-new-chat" @click="startDefaultChat"><Plus :size="16" />新建会话</button>
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
              <div class="home-prompts-title"><Sparkles :size="15" />大家都在问<em>· 点击直接提问</em></div>
              <div class="home-hot-grid">
                <button
                  v-for="(item, index) in hotQuestions"
                  :key="item"
                  type="button"
                  class="home-hot-item"
                  :class="{ 'is-active': activeHotQuestion === item }"
                  :aria-pressed="activeHotQuestion === item"
                  @click="askHotQuestion(item)"
                >
                  <span class="home-hot-index">{{ hotIndex(index) }}</span>
                  <span class="home-hot-text">{{ item }}</span>
                  <ArrowRight :size="15" aria-hidden="true" />
                </button>
              </div>
              <div class="home-tags">
                <span class="home-tags-label">热门标签</span>
                <!-- 只做悬停 / 按下时的颜色反馈：不保留选中态，鼠标移开即复原 -->
                <button
                  v-for="tag in hotTags"
                  :key="tag"
                  type="button"
                  class="home-tag"
                >{{ tag }}</button>
              </div>
            </section>
          </div>
          <div v-else class="conversation-thread">
            <div class="thread-title"><span class="live-dot"></span><span :title="currentSessionTitle">· {{ currentSessionTitle }}</span></div>
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
            <textarea id="chat-question" ref="questionInput" v-model="question" rows="2" :disabled="loadingHistory" :placeholder="selectedDocument ? `向《${selectedDocument.fileName}》提问 · Enter 发送` : sessionDocumentUnavailable ? '文档不可用，仍可闲聊或提出业务查询 · Enter 发送' : '请输入文档问题、业务查询或其他问题 · Enter 发送'" @keydown="handleChatKeydown"></textarea>
            <div class="composer-footer">
              <span><ShieldCheck :size="14" />AI 生成内容仅供参考，实际业务请以文件为准</span>
              <div class="composer-actions">
                <VoiceInput ref="voiceInput" v-model="question" :input="questionInput" :disabled="asking || loadingHistory" @busy="speechBusy = $event" @notice="notify" />
                <button :disabled="!question.trim() || asking || loadingHistory || speechBusy" @click="ask()">发送<ArrowUp :size="16" /></button>
              </div>
            </div>
          </div>
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
    </main>

    <KnowledgeGraph v-else-if="activePage === 'knowledge'" key="knowledge" />
    <DocumentReview v-else key="review" />
    </Transition>

  </div>

  <!-- 底部信息条：固定在窗口最下方，列表再长也不会被内容盖住 -->
  <div v-if="activePage !== 'home'" class="page-dock">
    <div v-if="activePage === 'documents'" class="prototype-note"><ShieldCheck :size="15" /><span>{{ backendEnabled ? '文件来自知识库，解析完成后支持问答。' : '当前后端未连接；不会模拟上传结果。' }}</span></div>
    <footer class="site-footer"><span>© 2026 农田 AI 助手</span><span class="footer-separator"></span><span>让农业知识服务更简单</span></footer>
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
  <!-- 历史对话的操作菜单：fixed 浮层，不占布局（不会把下面的会话挤下去）、层级高于侧栏；
       同样放在 .app-shell 之外：白色主题的 filter 会把 .app-shell 变成 fixed 的包含块，
       留在里面浮层就不再相对视口定位。position 由 toggleSessionMenu() 按按钮位置算出来。 -->
  <Teleport to="body">
    <div v-if="menuSession" class="session-menu" :style="menuStyle" @click.stop>
      <template v-if="deleteTargetId === menuSession.id">
        <button class="session-menu-item is-danger" type="button" :disabled="asking" @click="confirmDelete(menuSession)">确认删除</button>
        <button class="session-menu-item" type="button" @click="deleteTargetId = null">取消</button>
      </template>
      <template v-else>
        <button class="session-menu-item" type="button" @click="startRename(menuSession)">重命名</button>
        <button class="session-menu-item is-danger" type="button" @click="askDelete(menuSession.id)">删除</button>
      </template>
    </div>
  </Teleport>
  <Transition name="toast"><div v-if="toast" class="toast-message"><Check :size="16" />{{ toast }}</div></Transition>
</template>
