package edu.lcl.rag.controller;

import edu.lcl.rag.business.attendance.AttendanceGroupModels;
import edu.lcl.rag.business.attendance.AttendanceGroupQueryService;
import edu.lcl.rag.dto.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 必须提供项目和标段 ID；POST 仅传查询条件，业务库只执行参数化 SELECT。 */
@RestController
@RequestMapping("/api/v1/business/attendance-groups")
@ConditionalOnProperty(prefix = "business.datasource", name = "enabled", havingValue = "true")
public class AttendanceGroupQueryController {
    private final AttendanceGroupQueryService service;

    public AttendanceGroupQueryController(AttendanceGroupQueryService service) {
        this.service = service;
    }

    @PostMapping("/query")
    public ApiResponse<AttendanceGroupModels.Response> query(@RequestBody AttendanceGroupModels.Request request) {
        return ApiResponse.ok(service.query(request));
    }
}
