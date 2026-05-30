package com.example.orchestrator.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.example.orchestrator.model.enums.TaskStatus;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("task")
public class Task {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String identity;
    private Integer typeCode;
    private Integer priority;
    private TaskStatus status;
    private String inputDetail;
    private String transitionDetail;
    private String outputDetail;
    private String executorId;
    private Integer progress;
    private String failReason;
    private String callbackUrl;
    private Boolean callbackDone;
    private String createBy;
    private Long duration;
    private Instant createTime;
    private Instant prepareTime;
    private Instant waitingTime;
    private Instant startTime;
    private Instant postProcessTime;
    private Instant finishTime;
    private Instant updateTime;

    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }
}
