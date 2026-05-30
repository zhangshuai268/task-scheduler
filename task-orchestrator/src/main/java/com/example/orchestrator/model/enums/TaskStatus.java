package com.example.orchestrator.model.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TaskStatus {
    PENDING(0, "待处理"),
    PREPARING(1, "预处理中"),
    WAITING(2, "等待资源"),
    RUNNING(3, "运行中"),
    POST_PROCESSING(4, "后处理中"),
    COMPLETED(5, "已完成"),
    FAILED(6, "失败"),
    STOPPING(7, "停止中"),
    STOPPED(8, "已停止");

    @EnumValue
    private final int code;
    private final String description;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == STOPPED;
    }
}
