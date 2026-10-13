package edu.lcl.rag.service;

import edu.lcl.rag.dto.KnowledgeBaseCreateRequest;
import edu.lcl.rag.dto.KnowledgeBaseVO;
import edu.lcl.rag.entity.*;
import edu.lcl.rag.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository kbRepository;
    private final KbDocumentRepository documentRepository;
    private final DocChunkRepository chunkRepository;
    private final DocParentChunkRepository parentChunkRepository;
    private final MinioStorageService minioService;
    private final IndexService indexService;

    @Transactional
    public KnowledgeBase create(KnowledgeBaseCreateRequest req) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(req.getName());
        kb.setDescription(req.getDescription());
        KnowledgeBase saved = kbRepository.save(kb);
        log.info("[KB] 知识库创建：id={}，name={}", saved.getId(), saved.getName());
        return saved;
    }

    /** 列出所有未删除的知识库，不区分上传者。 */
    public List<KnowledgeBaseVO> listAll() {
        return kbRepository.findByIsDeletedFalse().stream().map(this::toVO).toList();
    }

    private KnowledgeBaseVO toVO(KnowledgeBase kb) {
        return KnowledgeBaseVO.builder()
                .id(kb.getId())
                .name(kb.getName())
                .description(kb.getDescription())
                .createdAt(kb.getCreatedAt())
                .build();
    }

    /**
     * 上传文档：存 MinIO → 创建待解析文档记录，不自动提交索引任务。
     */
    public KbDocument uploadDocument(Long kbId, org.springframework.web.multipart.MultipartFile file) {
        requireKnowledgeBase(kbId);
        // 校验文件类型
        String fileName = file.getOriginalFilename();
        validateFileType(fileName);

        // 上传到 MinIO
        String minioPath = minioService.upload(kbId, file);

        // 创建文档记录
        KbDocument doc = new KbDocument();
        doc.setKbId(kbId);
        doc.setFileName(fileName);
        doc.setFileType(detectFileType(fileName));
        doc.setFileSize(file.getSize());
        doc.setMinioPath(minioPath);
        KbDocument saved = documentRepository.save(doc);

        log.info("[KB] 文档上传：docId={}，fileName={}，kbId={}", saved.getId(), fileName, kbId);
        return saved;
    }

    /**
     * 手动启动待解析或失败文档的索引，锁定记录以避免重复提交。
     *
     * @param kbId 知识库 ID
     * @param docId 文档 ID
     */
    @Transactional
    public void startDocumentParsing(Long kbId, Long docId) {
        requireKnowledgeBase(kbId);
        KbDocument doc = documentRepository.findByIdForIndexLock(docId)
                .filter(document -> kbId.equals(document.getKbId())
                        && !Boolean.TRUE.equals(document.getIsDeleted()))
                .orElseThrow(() -> new IllegalArgumentException("该知识库下的文档不存在：" + docId));
        if (doc.getStatus() != KbDocument.DocumentStatus.PENDING
                && doc.getStatus() != KbDocument.DocumentStatus.FAILED) {
            throw new IllegalArgumentException("文档正在解析或已解析完成，请勿重复提交");
        }
        // 新版本使旧的失败重试任务失效，避免与手动重试并发。
        doc.setVersion(doc.getVersion() + 1);
        doc.setStatus(KbDocument.DocumentStatus.PROCESSING);
        doc.setErrorMsg(null);
        documentRepository.save(doc);
        indexService.submitIndexTask(docId);
    }

    /**
     * 删除文档：软删除文档记录 + 硬删除向量数据 + 删除 MinIO 文件。
     */
    @Transactional
    public void deleteDocument(Long docId) {
        KbDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("文档不存在：" + docId));

        // 软删除文档记录
        doc.setIsDeleted(true);
        documentRepository.save(doc);

        // 硬删除向量数据（向量数据不做软删除，占空间且不需要恢复）
        chunkRepository.deleteByDocId(docId);
        parentChunkRepository.deleteByDocId(docId);

        // 异步删除 MinIO 文件（不影响主流程）
        minioService.delete(doc.getMinioPath());

        log.info("[KB] 文档删除：docId={}，fileName={}", docId, doc.getFileName());
    }

    /**
     * 重建索引：递增版本号 → 提交新索引任务 → 新任务完成后旧分块自动清理。
     */
    @Transactional
    public void reindex(Long docId) {
        KbDocument doc = documentRepository.findById(docId)
                .orElseThrow(() -> new RuntimeException("文档不存在：" + docId));

        // 版本号递增，索引完成后旧版本分块会被清理
        doc.setVersion(doc.getVersion() + 1);
        doc.setStatus(KbDocument.DocumentStatus.PENDING);
        doc.setErrorMsg(null);
        documentRepository.save(doc);

        indexService.submitIndexTask(docId);
        log.info("[KB] 触发重建索引：docId={}，newVersion={}", docId, doc.getVersion());
    }

    private void validateFileType(String fileName) {
        if (fileName == null) throw new RuntimeException("文件名不能为空");
        String lower = fileName.toLowerCase();
        if (!lower.endsWith(".pdf") && !lower.endsWith(".docx") &&
            !lower.endsWith(".md")  && !lower.endsWith(".txt")) {
            throw new RuntimeException("不支持的文件类型，目前支持：PDF、DOCX、MD、TXT");
        }
    }

    public KnowledgeBase requireKnowledgeBase(Long kbId) {
        return kbRepository.findById(kbId)
                .filter(kb -> !Boolean.TRUE.equals(kb.getIsDeleted()))
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在：" + kbId));
    }

    public KbDocument requireDocument(Long kbId, Long docId) {
        requireKnowledgeBase(kbId);
        return documentRepository.findById(docId)
                .filter(doc -> kbId.equals(doc.getKbId()) && !Boolean.TRUE.equals(doc.getIsDeleted()))
                .orElseThrow(() -> new IllegalArgumentException("该知识库下的文档不存在：" + docId));
    }

    public void requireKnowledgeBases(List<Long> kbIds) {
        if (kbIds == null || kbIds.isEmpty() || kbIds.stream().anyMatch(id -> id == null)) {
            throw new IllegalArgumentException("请至少选择一个知识库");
        }
        kbIds.forEach(this::requireKnowledgeBase);
    }

    private String detectFileType(String fileName) {
        if (fileName == null) return "UNKNOWN";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf"))  return "PDF";
        if (lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".md"))   return "MD";
        if (lower.endsWith(".txt"))  return "TXT";
        return "UNKNOWN";
    }
}
