package com.example.orchestrator.model.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class TaskCreateRequest {
    @NotNull
    private Integer typeCode;
    private Map<String, Object> inputDetail;
    private Integer priority;
    private String createBy;
}
