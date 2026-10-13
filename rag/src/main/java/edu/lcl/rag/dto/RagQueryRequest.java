package edu.lcl.rag.dto;

import lombok.Data;

import java.util.List;

@Data
public class RagQueryRequest {
    private String question;
    private List<Long> kbIds;
}