package edu.lcl.rag.service;

import edu.lcl.rag.entity.KbDocument;
import edu.lcl.rag.entity.KnowledgeBase;
import edu.lcl.rag.repository.DocChunkRepository;
import edu.lcl.rag.repository.DocParentChunkRepository;
import edu.lcl.rag.repository.KbDocumentRepository;
import edu.lcl.rag.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 验证上传不自动解析、手动解析及失败重试的状态约束。
 *
 * @author Codex
 * @since 2026-10-12
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseServiceTest {

    /** 知识库数据访问。 */
    @Mock
    private KnowledgeBaseRepository kbRepository;
    /** 文档数据访问。 */
    @Mock
    private KbDocumentRepository documentRepository;
    /** 子块数据访问。 */
    @Mock
    private DocChunkRepository chunkRepository;
    /** 父块数据访问。 */
    @Mock
    private DocParentChunkRepository parentChunkRepository;
    /** 文件存储。 */
    @Mock
    private MinioStorageService minioService;
    /** 索引任务服务。 */
    @Mock
    private IndexService indexService;
    /** 被测服务。 */
    @InjectMocks
    private KnowledgeBaseService service;

    /** 准备有效知识库。 */
    @BeforeEach
    void setUp() {
        when(kbRepository.findById(1L)).thenReturn(Optional.of(new KnowledgeBase()));
    }

    /** 上传只保存原文件和待解析记录。 */
    @Test
    void uploadDoesNotStartParsing() {
        MockMultipartFile file = new MockMultipartFile("file", "policy.pdf", "application/pdf", new byte[]{1});
        when(minioService.upload(1L, file)).thenReturn("1/policy.pdf");
        when(documentRepository.save(any(KbDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        KbDocument document = service.uploadDocument(1L, file);

        assertThat(document.getStatus()).isEqualTo(KbDocument.DocumentStatus.PENDING);
        assertThat(document.getMinioPath()).isEqualTo("1/policy.pdf");
        verifyNoInteractions(indexService);
    }

    /** 待解析文档由用户手动启动。 */
    @Test
    void startsPendingDocument() {
        KbDocument document = document(KbDocument.DocumentStatus.PENDING);

        service.startDocumentParsing(1L, 2L);

        assertThat(document.getStatus()).isEqualTo(KbDocument.DocumentStatus.PROCESSING);
        assertThat(document.getVersion()).isEqualTo(2);
        verify(indexService).submitIndexTask(2L);
    }

    /** 失败文档重新解析时清除旧错误并递增版本。 */
    @Test
    void retriesFailedDocument() {
        KbDocument document = document(KbDocument.DocumentStatus.FAILED);
        document.setErrorMsg("旧解析错误");

        service.startDocumentParsing(1L, 2L);

        assertThat(document.getStatus()).isEqualTo(KbDocument.DocumentStatus.PROCESSING);
        assertThat(document.getErrorMsg()).isNull();
        assertThat(document.getVersion()).isEqualTo(2);
        verify(indexService).submitIndexTask(2L);
    }

    /** 解析中或已完成文档不得重复提交。 */
    @Test
    void rejectsProcessingAndCompletedDocuments() {
        for (KbDocument.DocumentStatus status : new KbDocument.DocumentStatus[]{
                KbDocument.DocumentStatus.PROCESSING, KbDocument.DocumentStatus.DONE}) {
            document(status);
            assertThatThrownBy(() -> service.startDocumentParsing(1L, 2L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("请勿重复提交");
        }
        verifyNoInteractions(indexService);
    }

    /** 不允许解析其他知识库下的文档。 */
    @Test
    void rejectsDocumentFromAnotherKnowledgeBase() {
        document(KbDocument.DocumentStatus.PENDING).setKbId(3L);

        assertThatThrownBy(() -> service.startDocumentParsing(1L, 2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文档不存在");
        verifyNoInteractions(indexService);
    }

    /**
     * 创建可通过锁查询读取的文档。
     *
     * @param status 文档状态
     * @return 测试文档
     */
    private KbDocument document(KbDocument.DocumentStatus status) {
        KbDocument document = new KbDocument();
        document.setId(2L);
        document.setKbId(1L);
        document.setStatus(status);
        when(documentRepository.findByIdForIndexLock(2L)).thenReturn(Optional.of(document));
        return document;
    }
}
