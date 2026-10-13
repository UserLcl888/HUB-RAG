package edu.lcl.rag.service.loader;

import edu.lcl.rag.entity.KbDocument;
import edu.lcl.rag.repository.DocChunkRepository;
import edu.lcl.rag.repository.KbDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

@SpringBootTest
@ActiveProfiles({"local", "sample-data"})
class DataInitTest {

    @Autowired
    private KbDocumentRepository documentRepository;

    @Autowired
    private DocChunkRepository chunkRepository;

    @Test
    void verifyTestDataLoaded() {
        long docCount = documentRepository.countByStatus(KbDocument.DocumentStatus.DONE);
        long chunkCount = chunkRepository.count();

        assertThat(docCount).isGreaterThanOrEqualTo(3);
        assertThat(chunkCount).isGreaterThan(10);

        System.out.printf("已索引文档数：%d，分块总数：%d%n", docCount, chunkCount);
    }
}
