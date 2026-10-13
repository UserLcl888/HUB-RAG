package edu.lcl.rag.dto;

import lombok.Data;

@Data
public class DocumentUploadResponse {
    private Long docId;
    private String fileName;
    private String status;       // PENDING（已上传，等待手动解析）
    private String message;

    public static DocumentUploadResponse submitted(Long docId, String fileName) {
        DocumentUploadResponse r = new DocumentUploadResponse();
        r.setDocId(docId);
        r.setFileName(fileName);
        r.setStatus("PENDING");
        r.setMessage("文档已上传，请点击解析按钮开始解析");
        return r;
    }
}
