package edu.lcl.rag.service;

import edu.lcl.rag.entity.*;
import edu.lcl.rag.repository.*;
import edu.lcl.rag.service.loader.ParseResult;
import edu.lcl.rag.service.splitter.ChunkResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexService {

    private final KbDocumentRepository documentRepository;
    private final IndexTaskRepository taskRepository;
    private final DocumentLoaderService loaderService;
    private final ParentChunkService parentChunkService;
    private final IndexWriteService indexWriteService;
    private final EmbeddingService embeddingService;
    private final MinioStorageService minioStorageService;
    private final IndexTaskLauncher taskLauncher;

    /**
     * 提交索引任务（支持直接传入文本，测试时跳过 MinIO）。
     */
    public void submitIndexTask(Long docId, String textContent) {
        Long taskId = createTask(docId);
        launchAfterCommit(() -> taskLauncher.launchWithText(taskId, docId, textContent));
    }

    /**
     * 提交索引任务（生产模式，从 MinIO 读取文件）。
     */
    public void submitIndexTask(Long docId) {
        Long taskId = createTask(docId);
        launchAfterCommit(() -> taskLauncher.launchFromMinio(taskId, docId));
    }

    private Long createTask(Long docId) {
        KbDocument doc = documentRepository.findById(docId).orElseThrow();
        IndexTask task = new IndexTask();
        task.setDocId(docId);
        task.setDocVersion(doc.getVersion());
        task.setTaskType("INDEX");
        return taskRepository.save(task).getId();
    }

    private void launchAfterCommit(Runnable launch) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    launch.run();
                }
            });
        } else {
            launch.run();
        }
    }

    /**
     * 从 MinIO 读取文件并执行索引（由 IndexTaskLauncher 异步调用）。
     */
    public void executeFromMinio(Long taskId, Long docId) {
        if (!startTask(taskId, docId)) {
            return;
        }
        try {
            KbDocument doc = documentRepository.findById(docId).orElseThrow();
            try (ByteArrayInputStream fileStream = new ByteArrayInputStream(
                    minioStorageService.download(doc.getMinioPath()))) {
                ParseResult parseResult = loaderService.load(
                        fileStream, doc.getFileName());
                doIndex(taskId, docId, parseResult, null);
            }
        } catch (Exception e) {
            markFailed(taskId, docId, "从MinIO读取文件失败：" + e.getMessage());
            retryIfPossible(taskId, docId, null);
        }
    }

    /**
     * 执行索引（直接使用文本内容，由 IndexTaskLauncher 异步调用）。
     */
    public void executeWithText(Long taskId, Long docId, String textContent) {
        if (!startTask(taskId, docId)) {
            return;
        }
        ParseResult parseResult = ParseResult.builder()
                .success(true)
                .pages(List.of(ParseResult.PageContent.builder()
                        .pageNum(1)
                        .text(textContent)
                        .build()))
                .totalPages(1)
                .build();
        doIndex(taskId, docId, parseResult, textContent);
    }

    /**
     * 核心索引逻辑：解析 → 聚合父块 → 父块内切子块 → 只向量化子块 → 事务写库。
     *
     * 设计思路：整个管道是"先算后删再写"的顺序，而不是"先删再算再写"。
     * 这样设计是为了保证可用性——如果 Embedding 阶段调 API 失败了，
     * 旧版本的分块还在，用户查询不受影响。
     * 只有当新向量算完、确定能写入了，才去删旧数据。
     */
    private void doIndex(Long taskId, Long docId, ParseResult parseResult, String textContent) {
        try {
            if (!parseResult.isSuccess()) {
                throw new RuntimeException("文档解析失败：" + parseResult.getErrorMsg());
            }

            List<ParentChunkService.ParentBlock> parents = parentChunkService.split(parseResult);
            List<ChunkResult> children = new ArrayList<>();
            List<Integer> parentIndices = new ArrayList<>();
            for (int parentIndex = 0; parentIndex < parents.size(); parentIndex++) {
                for (ChunkResult child : parentChunkService.children(parents.get(parentIndex))) {
                    children.add(child);
                    parentIndices.add(parentIndex);
                }
            }
            if (children.isEmpty()) {
                throw new RuntimeException("分块结果为空，文档可能无有效文本内容");
            }
            log.info("[IndexService] docId={}，父块={}，子块={}", docId, parents.size(), children.size());

            // 仅子块调用向量化接口；父块只用于回答时的上下文还原。
            // 标题路径参与向量化，库中仍保存原文片段，保证引用高亮可在父块中定位。
            List<String> texts = children.stream()
                    .map(child -> child.getSectionTitle() == null ? child.getContent()
                            : child.getSectionTitle() + "\n" + child.getContent())
                    .toList();
            List<float[]> embeddings = embeddingService.embedBatch(texts);
            if (embeddings.size() != children.size()) {
                throw new IllegalStateException("向量结果数量与子块数量不一致");
            }
            List<IndexWriteService.IndexedChild> indexed = new ArrayList<>();
            for (int i = 0; i < children.size(); i++) {
                indexed.add(new IndexWriteService.IndexedChild(parentIndices.get(i), children.get(i), embeddings.get(i)));
            }
            indexWriteService.replace(taskId, docId, taskRepository.findById(taskId).orElseThrow().getDocVersion(),
                    parents, indexed);
            log.info("[IndexService] 索引完成：docId={}，父块={}，子块={}", docId, parents.size(), children.size());

        } catch (Exception e) {
            log.error("[IndexService] 索引失败：docId={}，error={}", docId, e.getMessage(), e);
            markFailed(taskId, docId, e.getMessage());
            retryIfPossible(taskId, docId, textContent);
        }
    }

    private void markFailed(Long taskId, Long docId, String errorMsg) {
        IndexTask task = taskRepository.findById(taskId).orElseThrow();
        task.setStatus(IndexTask.TaskStatus.FAILED);
        task.setErrorMsg(errorMsg);
        task.setFinishedAt(LocalDateTime.now());
        taskRepository.save(task);

        documentRepository.updateIndexStatus(docId, task.getDocVersion(),
                KbDocument.DocumentStatus.FAILED, errorMsg);
    }

    private void retryIfPossible(Long taskId, Long docId, String textContent) {
        IndexTask task = taskRepository.findById(taskId).orElseThrow();
        if (task.canRetry() && isCurrentTask(task, docId)) {
            task.setRetryCount(task.getRetryCount() + 1);
            task.setStatus(IndexTask.TaskStatus.PENDING);
            taskRepository.save(task);
            log.info("[IndexService] 任务将重试：taskId={}，retryCount={}", taskId, task.getRetryCount());
            // 延迟重试（指数退避：1s, 2s, 4s）
            scheduleRetry(taskId, docId, task.getRetryCount(), textContent);
        }
    }

    /**
     * 延迟重试（指数退避）。
     * 这里直接用 Thread.sleep 而不是 ScheduledExecutorService，
     * 因为当前已经在 indexTaskExecutor 的异步线程里了，sleep 不会阻塞主线程。
     * 用 ScheduledExecutorService 反而要引入额外的线程池管理，过度设计了。
     */
    protected void scheduleRetry(Long taskId, Long docId, int retryCount, String textContent) {
        try {
            long delay = (long) Math.pow(2, retryCount - 1) * 1000;
            Thread.sleep(delay);
            if (textContent == null) {
                executeFromMinio(taskId, docId);
            } else {
                executeWithText(taskId, docId, textContent);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void updateTaskStatus(Long taskId, IndexTask.TaskStatus status) {
        taskRepository.findById(taskId).ifPresent(t -> {
            t.setStatus(status);
            if (status == IndexTask.TaskStatus.RUNNING) t.setStartedAt(LocalDateTime.now());
            if (status == IndexTask.TaskStatus.DONE)    t.setFinishedAt(LocalDateTime.now());
            taskRepository.save(t);
        });
    }

    private boolean startTask(Long taskId, Long docId) {
        IndexTask task = taskRepository.findById(taskId).orElseThrow();
        if (!isCurrentTask(task, docId)) {
            markFailed(taskId, docId, "索引任务已过期，文档已更新或删除");
            return false;
        }
        updateTaskStatus(taskId, IndexTask.TaskStatus.RUNNING);
        if (documentRepository.updateIndexStatus(docId, task.getDocVersion(),
                KbDocument.DocumentStatus.PROCESSING, null) == 1) {
            return true;
        }
        markFailed(taskId, docId, "索引任务已过期，文档已更新或删除");
        return false;
    }

    private boolean isCurrentTask(IndexTask task, Long docId) {
        return documentRepository.findById(docId)
                .filter(doc -> docId.equals(task.getDocId()) && task.getDocVersion() != null
                        && task.getDocVersion().equals(doc.getVersion())
                        && !Boolean.TRUE.equals(doc.getIsDeleted()))
                .isPresent();
    }
}
