package edu.lcl.rag.controller;

import edu.lcl.rag.business.project.ProjectQueryModels;
import edu.lcl.rag.business.project.ProjectQueryService;
import edu.lcl.rag.dto.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第一个业务查询接口：当前年度项目及标段、指定项目详情、名称模糊候选。
 * POST 仅用于传递结构化条件；业务库仅执行 SELECT，无保存、修改、建表或修复操作。
 * 本接口不持有会话；自然语言候选选择由项目工具适配器和现有聊天接口完成。
 */
@RestController
@RequestMapping("/api/v1/business/projects")
@ConditionalOnProperty(prefix = "business.datasource", name = "enabled", havingValue = "true")
public class ProjectQueryController {
    private final ProjectQueryService service;

    public ProjectQueryController(ProjectQueryService service) {
        this.service = service;
    }

    @PostMapping("/query")
    public ApiResponse<ProjectQueryModels.Response> query(
            @RequestBody(required = false) ProjectQueryModels.Request request) {
        return ApiResponse.ok(service.query(request));
    }
}
