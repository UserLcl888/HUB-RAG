package edu.lcl.rag.speech;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** 独立 ASR 配置，不改变现有 Spring AI 文字模型设置；禁止输出凭证。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "speech")
public class SpeechProperties {
    private String apiKey = "";
    private String workspaceId = "";
    private String model = "qwen-audio-3.1-asr-flash-message";
    private List<String> allowedOrigins = List.of("http://localhost:5173", "http://localhost:8080");
    private int maxConcurrent = 5;
    private int maxPerIp = 2;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && workspaceId != null && workspaceId.matches("[a-zA-Z0-9-]+")
                && model != null && !model.isBlank();
    }

    public String endpoint() {
        return "wss://" + workspaceId + ".cn-beijing.maas.aliyuncs.com/api-ws/v1/inference";
    }
}
