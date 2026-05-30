package com.example.orchestrator.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("task_type")
public class TaskType {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer typeCode;
    private String name;
    private String identityPrefix;
    private String imageConfig;
    private String ioConfig;
    private String startCmdConfig;
    private String envConfig;
    private String resourceConfig;
    private String jobConfig;
    private String inputDataConfig;
    private String callbackUrl;
    private String createBy;
    private Instant createTime;
    private String remark;
    private Integer status;
}
