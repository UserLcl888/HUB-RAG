package edu.lcl.rag.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** 自有浏览器协议；只转写音频，不操作聊天历史、RAG 或业务数据库。 */
@Component
public class SpeechWebSocketHandler extends BinaryWebSocketHandler {
    private static final long MAX_BYTES = 1_920_000;
    private final SpeechProperties properties;
    private final ObjectMapper mapper;
    private final Map<String, SpeechSession> sessions = new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService audioExecutor =
            Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform().daemon().name("speech-timeouts").unstarted(runnable));

    public SpeechWebSocketHandler(SpeechProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) throws Exception {
        socket.setTextMessageSizeLimit(2048);
        socket.setBinaryMessageSizeLimit(16384);
        // Spring 同源检查可能允许无 Origin 的非浏览器请求，此处按 Demo 约定明确拒绝。
        String origin = socket.getHandshakeHeaders().getOrigin();
        if (origin == null || !properties.getAllowedOrigins().contains(origin)) {
            socket.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        String ip = socket.getRemoteAddress() == null ? "unknown"
                : socket.getRemoteAddress().getAddress().getHostAddress();
        synchronized (sessions) {
            long perIp = sessions.values().stream().filter(session -> session.ip.equals(ip)).count();
            if (sessions.size() >= properties.getMaxConcurrent() || perIp >= properties.getMaxPerIp()) {
                socket.close(new CloseStatus(1013, "语音服务繁忙，请稍后重试"));
                return;
            }
            SpeechSession session = new SpeechSession(socket, ip);
            sessions.put(socket.getId(), session);
            session.armTimeout(10, "START_TIMEOUT", "语音连接等待超时，请重试");
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        SpeechSession session = sessions.get(socket.getId());
        if (session == null) {
            return;
        }
        try {
            JsonNode command = mapper.readTree(message.getPayload());
            if (command == null || !command.isObject()) {
                throw new IllegalArgumentException();
            }
            session.command(command);
        } catch (Exception error) {
            session.fail("INVALID_MESSAGE", "语音控制消息格式不正确");
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession socket, BinaryMessage message) {
        SpeechSession session = sessions.get(socket.getId());
        if (session != null) {
            byte[] bytes = new byte[message.getPayloadLength()];
            message.getPayload().get(bytes);
            session.audio(bytes);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        SpeechSession session = sessions.remove(socket.getId());
        if (session != null) {
            session.close();
        }
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable error) {
        SpeechSession session = sessions.get(socket.getId());
        if (session != null) {
            session.fail("CONNECTION_LOST", "语音连接中断，已保留确认文字");
        }
    }

    @PreDestroy
    public void shutdown() {
        sessions.values().forEach(SpeechSession::close);
        timers.shutdownNow();
        audioExecutor.shutdownNow();
    }

    private enum State { NEW, CONNECTING, READY, RECORDING, FINALIZING, CLOSED }

    private final class SpeechSession implements AsrProvider.Listener {
        private final WebSocketSession socket;
        private final String ip;
        private final TreeMap<Integer, String> confirmed = new TreeMap<>();
        private final Map<Integer, Integer> revisions = new HashMap<>();
        private State state = State.NEW;
        private String recordingId = "";
        private AsrProvider provider;
        private ScheduledFuture<?> timeout;
        private ScheduledFuture<?> recordingLimit;
        private long timeoutVersion;
        private long byteCount;
        private long startedNanos;

        private SpeechSession(WebSocketSession socket, String ip) {
            this.socket = new ConcurrentWebSocketSessionDecorator(socket, 5000, 131072);
            this.ip = ip;
        }

        private synchronized void command(JsonNode command) {
            if (state == State.CLOSED) {
                return;
            }
            String type = command.path("type").asText();
            String id = command.path("recordingId").asText();
            if ("start".equals(type) && state == State.NEW) {
                if (!id.matches("[a-zA-Z0-9-]{1,80}")
                        || !"pcm".equals(command.path("format").asText())
                        || command.path("sampleRate").asInt() != 16000
                        || command.path("channels").asInt() != 1
                        || command.path("bitsPerSample").asInt() != 16) {
                    fail("INVALID_FORMAT", "仅支持 16kHz、单声道、16bit PCM 音频");
                    return;
                }
                recordingId = id;
                if (!properties.isConfigured()) {
                    fail("NOT_CONFIGURED", "请先配置后端 ASR Workspace ID 和 API Key");
                    return;
                }
                state = State.CONNECTING;
                provider = new DashScopeAsrProvider(properties, audioExecutor);
                armTimeout(10, "READY_TIMEOUT", "语音服务连接超时，请重试");
                audioExecutor.execute(() -> provider.start(this));
                return;
            }
            if (!recordingId.equals(id) || state == State.NEW) {
                fail("INVALID_STATE", "语音会话不匹配，请重新开始");
            } else if ("cancel".equals(type)) {
                close();
            } else if ("stop".equals(type)) {
                if (state == State.READY || state == State.RECORDING) {
                    finish();
                } else if (state != State.FINALIZING) {
                    fail("INVALID_STATE", "语音尚未就绪，请重新开始");
                }
            } else {
                fail("INVALID_STATE", "语音消息顺序不正确，请重新开始");
            }
        }

        private synchronized void audio(byte[] pcm) {
            if (state == State.CLOSED || state == State.FINALIZING) {
                return;
            }
            if (state != State.READY && state != State.RECORDING) {
                fail("NOT_READY", "语音服务尚未就绪");
                return;
            }
            if (pcm.length == 0 || pcm.length > 16384 || pcm.length % 2 != 0) {
                fail("INVALID_AUDIO", "音频帧格式不正确");
                return;
            }
            if (state == State.READY) {
                state = State.RECORDING;
                startedNanos = System.nanoTime();
                // 给最后一帧少量网络时间；音频字节硬上限始终是 60 秒。
                recordingLimit = timers.schedule(this::finish, 61, TimeUnit.SECONDS);
            }
            int accepted = (int) Math.min(pcm.length, MAX_BYTES - byteCount);
            if (accepted > 0) {
                byte[] frame = accepted == pcm.length ? pcm : java.util.Arrays.copyOf(pcm, accepted);
                if (!provider.send(frame)) {
                    fail("AUDIO_BACKLOG", "音频发送积压，请检查网络后重试");
                    return;
                }
                byteCount += accepted;
            }
            if (byteCount >= MAX_BYTES || Duration.ofNanos(System.nanoTime() - startedNanos).toSeconds() >= 60) {
                finish();
            } else {
                armTimeout(10, "AUDIO_TIMEOUT", "麦克风音频中断，请重试");
            }
        }

        private synchronized void finish() {
            if (state != State.READY && state != State.RECORDING) {
                return;
            }
            state = State.FINALIZING;
            if (recordingLimit != null) {
                recordingLimit.cancel(false);
            }
            armTimeout(10, "FINAL_TIMEOUT", "识别收尾超时，已保留确认文字");
            if (!provider.finish()) {
                fail("AUDIO_BACKLOG", "音频收尾失败，已保留确认文字");
            }
        }

        @Override
        public synchronized void onReady() {
            if (state != State.CONNECTING) {
                return;
            }
            state = State.READY;
            armTimeout(10, "AUDIO_TIMEOUT", "未收到麦克风音频，请重试");
            send("ready", Map.of("maxDurationMs", 60000));
        }

        @Override
        public synchronized void onSentence(int segmentId, String text, boolean sentenceEnd) {
            if (state == State.CLOSED || confirmed.containsKey(segmentId)) {
                return;
            }
            if (sentenceEnd) {
                confirmed.put(segmentId, text);
            }
            int revision = revisions.merge(segmentId, 1, Integer::sum);
            send(sentenceEnd ? "final" : "partial",
                    Map.of("segmentId", segmentId, "revision", revision, "text", text));
        }

        @Override
        public synchronized void onComplete() {
            if (state == State.CLOSED) {
                return;
            }
            if (state != State.FINALIZING) {
                fail("ASR_INTERRUPTED", "语音任务提前结束，已保留确认文字");
                return;
            }
            send("complete", Map.of("text", confirmedText()));
            close();
        }

        @Override
        public void onError(String code, String message) {
            fail(code, message);
        }

        private String confirmedText() {
            return String.join("", confirmed.values());
        }

        private synchronized void armTimeout(int seconds, String code, String message) {
            if (timeout != null) {
                timeout.cancel(false);
            }
            long version = ++timeoutVersion;
            timeout = timers.schedule(() -> {
                synchronized (this) {
                    if (state != State.CLOSED && version == timeoutVersion) {
                        fail(code, message);
                    }
                }
            }, seconds, TimeUnit.SECONDS);
        }

        private synchronized void fail(String code, String message) {
            if (state == State.CLOSED) {
                return;
            }
            send("error", Map.of("code", code, "message", message, "confirmedText", confirmedText()));
            close();
        }

        private void send(String type, Map<String, Object> fields) {
            if (!socket.isOpen()) {
                return;
            }
            Map<String, Object> event = new HashMap<>(fields);
            event.put("type", type);
            event.put("recordingId", recordingId);
            try {
                socket.sendMessage(new TextMessage(mapper.writeValueAsString(event)));
            } catch (IOException | RuntimeException error) {
                close();
            }
        }

        private synchronized void close() {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CLOSED;
            if (timeout != null) {
                timeout.cancel(false);
            }
            if (recordingLimit != null) {
                recordingLimit.cancel(false);
            }
            if (provider != null) {
                provider.cancel();
            }
            sessions.remove(socket.getId(), this);
            try {
                if (socket.isOpen()) {
                    socket.close(CloseStatus.NORMAL);
                }
            } catch (IOException ignored) {
                // 资源状态已关闭；浏览器断线不需要再次关闭。
            }
        }
    }
}
