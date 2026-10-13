package edu.lcl.rag.service;

import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class IndexTaskLauncher {

    private final IndexService indexService;

    public IndexTaskLauncher(@Lazy IndexService indexService) {
        this.indexService = indexService;
    }

    @Async("indexTaskExecutor")
    public void launchFromMinio(Long taskId, Long docId) {
        indexService.executeFromMinio(taskId, docId);
    }

    @Async("indexTaskExecutor")
    public void launchWithText(Long taskId, Long docId, String textContent) {
        indexService.executeWithText(taskId, docId, textContent);
    }
}
