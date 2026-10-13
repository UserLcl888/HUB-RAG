package edu.lcl.rag.controller;

import edu.lcl.rag.dto.ApiResponse;
import edu.lcl.rag.dto.RagQueryRequest;
import edu.lcl.rag.dto.RagResponse;
import edu.lcl.rag.service.FullRagPipeline;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
public class RagQueryController {

    private final FullRagPipeline fullRagPipeline;

    @PostMapping("/query")
    public ApiResponse<RagResponse> query(@RequestBody RagQueryRequest req) {
        return ApiResponse.ok(fullRagPipeline.query(req.getQuestion(), req.getKbIds()));
    }

}
