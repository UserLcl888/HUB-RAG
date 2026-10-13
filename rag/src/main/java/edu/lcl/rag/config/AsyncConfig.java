package edu.lcl.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig {

    /**
     * 专用于文档索引的线程池，和业务请求线程池隔离。
     * 索引任务 IO 密集（解析文档 + 调 Embedding API），线程数可以多一点。
     */
    @Bean("indexTaskExecutor")
    public Executor indexTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("index-");
        // 队列满了，由提交线程自己执行，不丢任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
/**
 * IndexTaskLauncher使用了
 * Controller                       Service                      IndexTaskLauncher            IndexService
   │                                │                              │                           │
   │─ upload(file) ────────────────→│                              │                           │
   │                                │─ 保存文件到 MinIO             │                           │
   │                                │─ 创建 doc 记录(状态=PENDING)  │                           │
   │                                │─ 创建 IndexTask 记录          │                           │
   │                                │                              │                           │
   │                                │─ launchFromMinio(…) ────────→│                           │
   │                                │                              │                           │
   │                                │  ← 立即返回 void ←───────────│ @Async 提交到线程池        │
   │  ← ApiResponse(已提交) ←──────│                              │   ↓ 线程切换               │
   │                                │                              │                            │
   │                                │                              │ [index-1] 线程开始         │
   │                                │                              │ 无需传递登录上下文            │
   │                                │                              │─ executeFromMinio() ──────→│
   │                                │                              │                            │─ 下载文件
   │                                │                              │                            │─ 解析 PDF/DOCX
   │                                │                              │                            │─ 分块
   │                                │                              │                            │─ 批量 Embedding
   │                                │                              │                            │─ 删除旧版本 chunks
   │                                │                              │                            │─ 批量 insert 新 chunks
   │                                │                              │                            │─ 更新 doc 状态=DONE
   │                                │                              │  ← 完成 ←─────────────────│
   │                                │                              │                            │

 */
