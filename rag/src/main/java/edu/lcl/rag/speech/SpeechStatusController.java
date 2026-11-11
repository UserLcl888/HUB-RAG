package edu.lcl.rag.speech;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class SpeechStatusController {
    private final SpeechProperties properties;

    public SpeechStatusController(SpeechProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/v1/speech/status")
    public Map<String, Object> status() {
        return Map.of("configured", properties.isConfigured(), "maxDurationMs", 60000,
                "message", properties.isConfigured() ? "" : "请先配置后端 ASR Workspace ID 和 API Key");
    }
}
