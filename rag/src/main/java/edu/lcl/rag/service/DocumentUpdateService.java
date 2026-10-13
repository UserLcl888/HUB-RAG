package edu.lcl.rag.service;

import edu.lcl.rag.entity.KbDocument;
import edu.lcl.rag.repository.KbDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档更新的编排 Service。
 * 自身不放任何事务——所有事务边界都在 DocumentTxService 里。
 * <p>
 * 编排顺序：
 * 1. 跨 Bean 调用 txService 的事务方法——事务在那一刻完整启动并提交
 * 2. 事务提交后再触发异步索引——保证异步线程从 DB 能读到最新数据
 * 3. 最后清理旧的 MinIO 文件——异步任务读的是新 minioPath，与旧文件无关
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentUpdateService {

    private final DocumentTxService txService;
    private final KbDocumentRepository documentRepository;
    private final MinioStorageService minioService;
    private final IndexService indexService;

    /**
     * 替换文档内容，保持文档 ID 不变。
     */
    public KbDocument replaceDocument(Long docId, MultipartFile newFile) {
        String oldMinioPath = txService.updateDocumentRecord(docId, newFile);
        indexService.submitIndexTask(docId);
        minioService.delete(oldMinioPath);
        return documentRepository.findById(docId).orElseThrow();
    }

    /**
     * 强制重建索引（文件字节未变、但解析或分块策略变了等场景）。
     */
    public void forceReindexAndSubmit(Long docId) {
        txService.forceReindex(docId);
        indexService.submitIndexTask(docId);
        log.info("[DocumentUpdate] 强制重建索引已提交：docId={}", docId);
    }
}