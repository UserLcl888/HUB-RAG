package edu.lcl.rag.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * 独立问答页（悬浮窗内嵌内容）入口：{@code http://<后端地址>:8080/emb}。
 *
 * <p>页面本体是静态资源 {@code classpath:/static/emb/index.html}（自包含，无构建、无外部依赖）。
 * 这里只补两个显式入口 {@code /emb} 与 {@code /emb/}：
 * 目录形式的请求不会被静态资源处理器解析成 index.html，必须显式映射，
 * 这样前端同学拿到的地址不需要带 {@code /index.html} 或结尾斜杠。
 *
 * <p>页面所有请求（{@code /api/v1/kb}、{@code /api/v1/chat/stream}）与入口同源，
 * 不需要额外的 CORS 配置；页面本身不写入任何会话/资料数据，只是复用现有问答接口。
 */
@RestController
public class EmbPageController {

    /** 与 static 目录下的实际路径保持一致 */
    private static final String PAGE_LOCATION = "static/emb/index.html";

    @GetMapping({"/emb", "/emb/"})
    public ResponseEntity<Resource> embPage() {
        Resource page = new ClassPathResource(PAGE_LOCATION);
        if (!page.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                // 明确 UTF-8：页面里全是中文文案
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                // 页面还在联调期：不加长缓存，浏览器每次都回源校验
                .cacheControl(CacheControl.noCache())
                .body(page);
    }
}
