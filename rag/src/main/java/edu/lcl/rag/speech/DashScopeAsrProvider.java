package edu.lcl.rag.speech;

import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.common.DashScopeResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.protocol.ApiServiceOption;
import com.alibaba.dashscope.protocol.ConnectionOptions;
import com.alibaba.dashscope.protocol.FullDuplexRequest;
import com.alibaba.dashscope.protocol.Protocol;
import com.alibaba.dashscope.protocol.StreamingMode;
import com.alibaba.dashscope.protocol.okhttp.OkHttpClientFactory;
import com.alibaba.dashscope.protocol.okhttp.OkHttpWebSocketClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import lombok.experimental.SuperBuilder;
import okhttp3.WebSocket;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 官方 SDK 双向流适配。SDK 生成任务协议、发送 PCM 和结束指令；
 * 只监听生命周期，弥补高层 Recognition 未暴露 task-started/finished 的限制。
 */
public final class DashScopeAsrProvider implements AsrProvider {
    private static final byte[] END = new byte[0];
    private static final okhttp3.OkHttpClient AUDIO_CLIENT = OkHttpClientFactory.getNewOkHttpClient(
            ConnectionOptions.builder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .writeTimeout(Duration.ofSeconds(5))
                    .readTimeout(Duration.ofSeconds(80))
                    .build());
    private final SpeechProperties properties;
    private final Executor executor;
    private final ArrayBlockingQueue<byte[]> audio = new ArrayBlockingQueue<>(21);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean ending = new AtomicBoolean();
    private volatile ObservedSdkClient client;
    private volatile Disposable subscription;

    public DashScopeAsrProvider(SpeechProperties properties, Executor executor) {
        this.properties = properties;
        this.executor = executor;
    }

    @SuperBuilder
    private static class StreamingRecognitionParam extends RecognitionParam {
        private final Flowable<Object> audioSource;

        @Override
        public Flowable<Object> getStreamingData() {
            return audioSource;
        }
    }

    @Override
    public void start(Listener listener) {
        if (cancelled.get()) {
            return;
        }
        try {
            Flowable<Object> source = Flowable.create(emitter -> {
                while (!cancelled.get() && !emitter.isCancelled()) {
                    byte[] frame = audio.take();
                    if (frame == END) {
                        emitter.onComplete();
                        return;
                    }
                    emitter.onNext(ByteBuffer.wrap(frame));
                }
            }, BackpressureStrategy.ERROR);
            var parameters = StreamingRecognitionParam.builder()
                    .model(properties.getModel())
                    .apiKey(properties.getApiKey())
                    .format("pcm")
                    .sampleRate(16000)
                    .parameter("intermediate_result_enabled", true)
                    .parameter("language_hints", List.of("zh"))
                    .parameter("keep_dialect", false)
                    .parameter("heartbeat", true)
                    .parameter("max_sentence_silence", 800)
                    .audioSource(source)
                    .build();
            var options = ApiServiceOption.builder()
                    .protocol(Protocol.WEBSOCKET)
                    .streamingMode(StreamingMode.DUPLEX)
                    .taskGroup("audio").task("asr").function("recognition")
                    .passTaskStarted(true)
                    .baseWebSocketUrl(properties.endpoint())
                    .build();
            ObservedSdkClient sdk = new ObservedSdkClient(listener);
            client = sdk;
            // 先订阅再启动网络请求，防止极快的启动/失败事件先于订阅者到达。
            subscription = sdk.duplex(new FullDuplexRequest(parameters, options)).subscribe(
                    result -> onResult(result, listener),
                    error -> {
                        if (!cancelled.get()) {
                            reportError(error, listener);
                        }
                    },
                    () -> {
                        if (!cancelled.get() && !sdk.finished.get()) {
                            listener.onError("ASR_INTERRUPTED", "语音连接意外关闭，已保留确认文字");
                        }
                    });
            if (cancelled.get()) {
                sdk.cancel();
                subscription.dispose();
            } else {
                sdk.launch();
            }
        } catch (Exception error) {
            if (!cancelled.get()) {
                listener.onError("ASR_START_FAILED", "语音服务启动失败，请检查后端配置或网络");
            }
        }
    }

    private void reportError(Throwable error, Listener listener) {
        String detail = "";
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 6; depth += 1, cause = cause.getCause()) {
            if (cause instanceof ApiException api && api.getStatus() != null) {
                detail += " " + api.getStatus().getCode() + " " + api.getStatus().getStatusCode()
                        + " " + api.getStatus().getMessage();
            }
        }
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("401") || lower.contains("403") || lower.contains("apikey")
                || lower.contains("api_key") || lower.contains("unauthorized")
                || lower.contains("forbidden") || lower.contains("permission")) {
            listener.onError("ASR_AUTH_FAILED", "语音服务无调用权限，请检查北京地域 API Key、业务空间及模型开通情况");
        } else if (lower.contains("429") || lower.contains("throttl") || lower.contains("ratelimit")) {
            listener.onError("ASR_RATE_LIMIT", "语音服务限流，请稍后重试");
        } else if (lower.contains("quota") || lower.contains("balance") || lower.contains("arrear")) {
            listener.onError("ASR_QUOTA", "语音服务额度不足，请检查账号额度或欠费情况");
        } else {
            listener.onError("ASR_FAILED", "语音服务调用失败，请检查配置或网络后重试");
        }
    }

    private void onResult(DashScopeResult result, Listener listener) {
        if (cancelled.get() || result.getOutput() == null) {
            return;
        }
        JsonObject output = JsonParser.parseString(result.getOutput().toString()).getAsJsonObject();
        if (!output.has("sentence") || !output.get("sentence").isJsonObject()) {
            return;
        }
        JsonObject sentence = output.getAsJsonObject("sentence");
        if ((sentence.has("heartbeat") && sentence.get("heartbeat").getAsBoolean())
                || !sentence.has("text") || sentence.get("text").isJsonNull()
                || !sentence.has("sentence_id")) {
            return;
        }
        String text = sentence.get("text").getAsString();
        if (!text.isBlank()) {
            listener.onSentence(sentence.get("sentence_id").getAsInt(), text,
                    sentence.has("sentence_end") && sentence.get("sentence_end").getAsBoolean());
        }
    }

    @Override
    public boolean send(byte[] pcm) {
        // 留一个队列槽位给 END，避免正常停止时队列刚好填满。
        return !cancelled.get() && !ending.get() && audio.size() < 20 && audio.offer(pcm);
    }

    @Override
    public boolean finish() {
        return !cancelled.get() && ending.compareAndSet(false, true) && audio.offer(END);
    }

    @Override
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        ObservedSdkClient sdk = client;
        if (sdk != null) {
            sdk.cancel();
        }
        Disposable current = subscription;
        if (current != null) {
            current.dispose();
        }
        audio.clear();
        audio.offer(END);
    }

    private final class ObservedSdkClient extends OkHttpWebSocketClient {
        private final Listener listener;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final CompletableFuture<Void> sendFuture = new CompletableFuture<>();
        private FullDuplexRequest pendingRequest;

        private ObservedSdkClient(Listener listener) {
            super(AUDIO_CLIENT, true);
            this.listener = listener;
        }

        @Override
        protected CompletableFuture<Void> sendStreamRequest(FullDuplexRequest request) {
            pendingRequest = request;
            return sendFuture;
        }

        private void launch() {
            executor.execute(() -> {
                try {
                    if (!cancelled.get()) {
                        executeStreamRequest(pendingRequest);
                    }
                    sendFuture.complete(null);
                } catch (Exception error) {
                    sendFuture.completeExceptionally(error);
                    if (!cancelled.get()) {
                        listener.onError("ASR_CONNECTION_FAILED", "语音服务连接失败，请重试");
                    }
                }
            });
        }

        @Override
        protected void onBeforeSendStartMessage(JsonObject message) {
            if (cancelled.get()) {
                throw new java.util.concurrent.CancellationException();
            }
        }

        @Override
        public void onMessage(WebSocket socket, String text) {
            if (cancelled.get()) {
                return;
            }
            // 不接管协议，只读取 SDK 已接收消息的事件头；识别结果仍走 SDK 回调。
            String event;
            try {
                event = JsonParser.parseString(text).getAsJsonObject()
                        .getAsJsonObject("header").get("event").getAsString();
            } catch (RuntimeException error) {
                listener.onError("ASR_BAD_RESPONSE", "语音服务返回异常，请重试");
                return;
            }
            if ("task-finished".equals(event)) {
                finished.set(true);
            }
            super.onMessage(socket, text);
            if (cancelled.get()) {
                return;
            }
            if ("task-started".equals(event)) {
                listener.onReady();
            } else if ("task-finished".equals(event)) {
                listener.onComplete();
            }
        }
    }
}
