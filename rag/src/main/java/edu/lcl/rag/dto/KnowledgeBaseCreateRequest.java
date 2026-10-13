package edu.lcl.rag.dto;

import lombok.Data;

@Data
public class KnowledgeBaseCreateRequest {
    private String name;
    private String description;
}
