package com.example.orchestrator.model.dto;

import com.example.orchestrator.model.entity.Task;
import com.example.orchestrator.model.enums.TaskStatus;
import lombok.Data;

import java.time.Instant;

@Data
public class TaskResponse {
    private Long id;
    private String identity;
    private Integer typeCode;
    private TaskStatus status;
    private Integer progress;
    private String failReason;
    private String executorId;
    private Instant createTime;
    private Instant startTime;
    private Instant finishTime;
    private Long duration;

    public static TaskResponse from(Task t) {
        TaskResponse r = new TaskResponse();
        r.setId(t.getId());
        r.setIdentity(t.getIdentity());
        r.setTypeCode(t.getTypeCode());
        r.setStatus(t.getStatus());
        r.setProgress(t.getProgress());
        r.setFailReason(t.getFailReason());
        r.setExecutorId(t.getExecutorId());
        r.setCreateTime(t.getCreateTime());
        r.setStartTime(t.getStartTime());
        r.setFinishTime(t.getFinishTime());
        r.setDuration(t.getDuration());
        return r;
    }
}
