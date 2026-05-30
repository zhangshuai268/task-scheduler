package com.example.orchestrator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.orchestrator.engine.TaskStateMachine;
import com.example.orchestrator.mapper.TaskMapper;
import com.example.orchestrator.mapper.TaskTypeMapper;
import com.example.orchestrator.model.dto.TaskCreateRequest;
import com.example.orchestrator.model.entity.Task;
import com.example.orchestrator.model.entity.TaskType;
import com.example.orchestrator.model.enums.TaskStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskMapper taskMapper;
    private final TaskTypeMapper taskTypeMapper;
    private final TaskStateMachine stateMachine;
    private final ObjectMapper objectMapper;

    @Transactional
    public Task createTask(TaskCreateRequest request) {
        TaskType taskType = getTaskType(request.getTypeCode());
        if (taskType.getStatus() != 1) {
            throw new IllegalArgumentException("Task type is disabled:" + taskType.getName());
        }

        Task task = new Task();
        task.setTypeCode(request.getTypeCode());
        task.setPriority(request.getPriority() != null ? request.getPriority() : 100);
        task.setStatus(TaskStatus.PENDING);
        task.setCallbackUrl(taskType.getCallbackUrl());
        task.setCreateBy(request.getCreateBy());
        task.setProgress(0);
        task.setCallbackDone(false);
        task.setInputDetail("{}");
        task.setTransitionDetail("{}");
        task.setOutputDetail("{}");

        try {
            if (request.getInputDetail() != null) {
                task.setInputDetail(objectMapper.writeValueAsString(request.getInputDetail()));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid inputDetail: " + e.getMessage());
        }

        Instant now = Instant.now();
        task.setCreateTime(now);
        task.setUpdateTime(now);
        task.setIdentity("temp-" + System.currentTimeMillis());
        taskMapper.insert(task);

        String prefix = taskType.getIdentityPrefix();
        if (prefix == null || prefix.isBlank()) prefix = "job";
        task.setIdentity(String.format("%s-%d-%d", prefix.trim(), task.getTypeCode(), task.getId()));
        taskMapper.updateById(task);

        log.info("Task created: identity={}, typeCode={}", task.getIdentity(), taskType.getTypeCode());
        return task;
    }

    public ResolvedTaskConfig resolveConfig(Task task) {
        TaskType taskType = getTaskType(task.getTypeCode());
        Map<String, String> vars = buildVars(task);

        ResolvedTaskConfig config = new ResolvedTaskConfig();
        config.setImage(extractImageUrl(taskType.getImageConfig()));
        config.setIoConfig(TemplateRenderer.render(taskType.getIoConfig(), vars));
        config.setEnvConfig(TemplateRenderer.render(taskType.getEnvConfig(), vars));
        config.setResourceConfig(taskType.getResourceConfig());
        config.setStartCmdConfig(taskType.getStartCmdConfig());
        config.setJobConfig(taskType.getJobConfig());
        config.setInputDataConfig(taskType.getInputDataConfig());
        config.setTaskType(taskType);
        return config;
    }

    public Task getTask(String identity) {
        Task task = taskMapper.selectOne(new LambdaQueryWrapper<Task>().eq(Task::getIdentity, identity));
        if (task == null) throw new IllegalArgumentException("Task not found: " + identity);
        return task;
    }

    public List<Task> listActiveTasks() {
        return taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .notIn(Task::getStatus, stateMachine.terminalStatuses())
                .orderByAsc(Task::getPriority).orderByAsc(Task::getCreateTime));
    }

    public List<Task> listFinishedUnCallbackTasks() {
        return taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .in(Task::getStatus, stateMachine.terminalStatuses())
                .eq(Task::getCallbackDone, false)
                .isNotNull(Task::getCallbackUrl).ne(Task::getCallbackUrl, ""));
    }

    @Transactional
    public void stopTask(String identity) {
        Task task = getTask(identity);
        if (task.isTerminal()) throw new IllegalStateException("Task already terminal: " + task.getStatus());
        task.setStatus(stateMachine.canTransition(task.getStatus(), TaskStatus.STOPPING)
                ? TaskStatus.STOPPING : TaskStatus.STOPPED);
        if (task.getStatus() == TaskStatus.STOPPED) task.setFailReason("Manually stopped");
        task.setUpdateTime(Instant.now());
        taskMapper.updateById(task);
    }

    public TaskType getTaskType(Integer typeCode) {
        TaskType t = taskTypeMapper.selectOne(new LambdaQueryWrapper<TaskType>().eq(TaskType::getTypeCode, typeCode));
        if (t == null) throw new IllegalArgumentException("Task type not found: " + typeCode);
        return t;
    }

    public List<TaskType> listEnabledTaskTypes() {
        return taskTypeMapper.selectList(new LambdaQueryWrapper<TaskType>().eq(TaskType::getStatus, 1));
    }

    private Map<String, String> buildVars(Task task) {
        Map<String, String> vars = new HashMap<>();
        vars.put("jobIdentity", task.getIdentity());
        try {
            Map<String, Object> detail = objectMapper.readValue(task.getInputDetail(), new TypeReference<>() {});
            detail.forEach((k, v) -> { if (v != null) vars.put(k, v.toString()); });
        } catch (Exception e) {
            log.warn("Failed to parse inputDetail for {}: {}", task.getIdentity(), e.getMessage());
        }
        return vars;
    }

    private String extractImageUrl(String imageConfig) {
        try {
            Map<String, Object> c = objectMapper.readValue(imageConfig, new TypeReference<>() {});
            Object url = c.get("ImageUrl");
            return url != null ? url.toString() : null;
        } catch (Exception e) { return null; }
    }

    @Data
    public static class ResolvedTaskConfig {
        private String image;
        private String ioConfig;
        private String envConfig;
        private String resourceConfig;
        private String startCmdConfig;
        private String jobConfig;
        private String inputDataConfig;
        private TaskType taskType;
    }
}
