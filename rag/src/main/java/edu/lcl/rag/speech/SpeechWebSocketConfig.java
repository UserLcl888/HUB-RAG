package edu.lcl.rag.speech;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties(SpeechProperties.class)
public class SpeechWebSocketConfig implements WebSocketConfigurer {
    private final SpeechWebSocketHandler handler;
    private final SpeechProperties properties;

    public SpeechWebSocketConfig(SpeechWebSocketHandler handler, SpeechProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/speech/recognize")
                .setAllowedOrigins(properties.getAllowedOrigins().toArray(String[]::new));
    }
}
