package com.example.orchestrator.engine;

import com.example.orchestrator.mapper.TaskMapper;
import com.example.orchestrator.model.entity.Task;
import com.example.orchestrator.model.enums.TaskStatus;
import com.example.orchestrator.service.TaskService;
import com.example.orchestrator.service.TaskService.ResolvedTaskConfig;
import com.example.orchestrator.service.WorkerClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 状态分派:
 *   PENDING         → 验证配置 → WAITING
 *   WAITING         → 提交到 workerUrl 指定的执行层 → RUNNING
 *   RUNNING         → 查询执行层状态 → POST_PROCESSING / FAILED
 *   POST_PROCESSING → 流转完成 → COMPLETED
 *   STOPPING        → 通知执行层停止 → STOPPED
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskEngine {

    private final TaskStateMachine stateMachine;
    private final TaskMapper taskMapper;
    private final TaskService taskService;
    private final WorkerClient workerClient;
    private final ObjectMapper objectMapper;

    public void process(Task task) {
        if (task.isTerminal()) return;

        try {
            ResolvedTaskConfig config = taskService.resolveConfig(task);

            switch (task.getStatus()) {
                case PENDING -> handlePrepare(task, config);
                case WAITING -> handleStartup(task, config);
                case RUNNING -> handleQueryStatus(task, config);
                case POST_PROCESSING -> handlePostProcess(task);
                case STOPPING -> handleStop(task, config);
                default -> {}
            }
        } catch (Exception e) {
            log.error("[{}] Engine error at {}: {}", task.getIdentity(), task.getStatus(), e.getMessage(), e);
            updateStatus(task, TaskStatus.FAILED, e.getMessage());
        }
    }

    /** PENDING → WAITING */
    private void handlePrepare(Task task, ResolvedTaskConfig config) {
        log.info("[{}] Preparing", task.getIdentity());
        if (config.getImage() == null || config.getImage().isBlank()) {
            updateStatus(task, TaskStatus.FAILED, "Missing image in task type config");
            return;
        }
        String workerUrl = extractWorkerUrl(config.getJobConfig());
        if (workerUrl == null || workerUrl.isBlank()) {
            updateStatus(task, TaskStatus.FAILED, "Missing workerUrl in task type jobConfig");
            return;
        }
        updateStatus(task, TaskStatus.WAITING, null);
    }

    /** WAITING → RUNNING */
    private void handleStartup(Task task, ResolvedTaskConfig config) throws Exception {
        String workerUrl = extractWorkerUrl(config.getJobConfig());

        log.info("[{}] Submitting to worker: {}", task.getIdentity(), workerUrl);

        Map<String, Object> payload = new HashMap<>();
        payload.put("taskIdentity", task.getIdentity());
        payload.put("image", config.getImage());
        payload.put("ioConfig", config.getIoConfig());
        payload.put("envConfig", config.getEnvConfig());
        payload.put("resourceConfig", config.getResourceConfig());
        payload.put("jobConfig", config.getJobConfig());
        payload.put("inputDetail", task.getInputDetail());

        String executorId = workerClient.submitTask(workerUrl, payload);

        Task update = taskMapper.selectById(task.getId());
        update.setStatus(TaskStatus.RUNNING);
        update.setExecutorId(executorId);
        update.setStartTime(Instant.now());
        update.setUpdateTime(Instant.now());
        taskMapper.updateById(update);

        log.info("[{}] Started, executorId={}", task.getIdentity(), executorId);
    }

    /** RUNNING → POST_PROCESSING / FAILED */
    private void handleQueryStatus(Task task, ResolvedTaskConfig config) throws Exception {
        if (task.getExecutorId() == null) {
            updateStatus(task, TaskStatus.FAILED, "Missing executorId");
            return;
        }

        String workerUrl = extractWorkerUrl(config.getJobConfig());
        Map<String, Object> statusResp = workerClient.queryStatus(workerUrl, task.getExecutorId());

        String workerStatus = String.valueOf(statusResp.getOrDefault("status", "unknown"));
        int exitCode = statusResp.get("exitCode") instanceof Number n ? n.intValue() : -1;
        int progress = statusResp.get("progress") instanceof Number n ? n.intValue() : task.getProgress();

        switch (workerStatus) {
            case "running" -> {
                Task update = taskMapper.selectById(task.getId());
                update.setProgress(progress);
                update.setUpdateTime(Instant.now());
                taskMapper.updateById(update);
            }
            case "exited" -> {
                if (exitCode == 0) {
                    updateStatus(task, TaskStatus.POST_PROCESSING, null);
                } else {
                    updateStatus(task, TaskStatus.FAILED, "Container exited with code: " + exitCode);
                }
            }
            default -> log.debug("[{}] Worker status: {}", task.getIdentity(), workerStatus);
        }
    }

    /** POST_PROCESSING → COMPLETED */
    private void handlePostProcess(Task task) {
        log.info("[{}] Post-processing complete", task.getIdentity());
        updateStatus(task, TaskStatus.COMPLETED, null);
    }

    /** STOPPING → STOPPED */
    private void handleStop(Task task, ResolvedTaskConfig config) throws Exception {
        if (task.getExecutorId() != null) {
            String workerUrl = extractWorkerUrl(config.getJobConfig());
            if (workerUrl != null) {
                workerClient.stopTask(workerUrl, task.getExecutorId());
            }
        }
        updateStatus(task, TaskStatus.STOPPED, "Stopped");
    }

    // ========== 内部方法 ==========

    @Transactional
    public void updateStatus(Task task, TaskStatus targetStatus, String failReason) {
        Task fresh = taskMapper.selectById(task.getId());
        if (fresh == null || fresh.isTerminal()) return;

        if (!stateMachine.canTransition(fresh.getStatus(), targetStatus)) {
            log.warn("[{}] Invalid transition: {} → {}", fresh.getIdentity(), fresh.getStatus(), targetStatus);
            return;
        }

        TaskStatus oldStatus = fresh.getStatus();
        fresh.setStatus(targetStatus);
        if (failReason != null) fresh.setFailReason(failReason);
        updateTimestamps(fresh, targetStatus);
        if (fresh.getCreateTime() != null) {
            fresh.setDuration(Duration.between(fresh.getCreateTime(), Instant.now()).toMinutes());
        }
        fresh.setUpdateTime(Instant.now());
        taskMapper.updateById(fresh);

        log.info("[{}] Status: {} → {}", fresh.getIdentity(), oldStatus, targetStatus);
    }

    private void updateTimestamps(Task task, TaskStatus status) {
        Instant now = Instant.now();
        switch (status) {
            case PREPARING -> task.setPrepareTime(now);
            case WAITING -> task.setWaitingTime(now);
            case RUNNING -> task.setStartTime(now);
            case POST_PROCESSING -> task.setPostProcessTime(now);
            case COMPLETED, FAILED, STOPPED -> task.setFinishTime(now);
            default -> {}
        }
    }

    private String extractWorkerUrl(String jobConfig) {
        try {
            Map<String, Object> config = objectMapper.readValue(jobConfig, new TypeReference<>() {});
            Object url = config.get("workerUrl");
            return url != null ? url.toString() : null;
        } catch (Exception e) { return null; }
    }
}
