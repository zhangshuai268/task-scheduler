package com.example.orchestrator.engine;

import com.example.orchestrator.model.entity.Task;
import com.example.orchestrator.service.CallbackService;
import com.example.orchestrator.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class TaskScheduler {

    private final TaskEngine taskEngine;
    private final TaskService taskService;
    private final CallbackService callbackService;

    @Value("${scheduler.max-concurrent-tasks:20}")
    private int maxConcurrentTasks;

    private final Set<Long> processingTaskIds = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedRateString = "${scheduler.poll-rate-ms:5000}")
    public void pollAndProcess() {
        List<Task> activeTasks = taskService.listActiveTasks();
        for (Task task : activeTasks) {
            if (processingTaskIds.size() >= maxConcurrentTasks) {
                break;
            }
            if (!processingTaskIds.add(task.getId())) {
                continue;
            }
            CompletableFuture.runAsync(() -> {
                try {
                    taskEngine.process(task);
                } catch (Exception e) {
                    log.error("[{}] Scheduler error: {}", task.getIdentity(), e.getMessage(), e);
                } finally {
                    processingTaskIds.remove(task.getId());
                }
            });
        }
    }

    @Scheduled(fixedRateString = "${scheduler.poll-rate-ms:5000}")
    public void pollAndCallback() {
        taskService.listFinishedUnCallbackTasks().forEach(callbackService::doCallback);
    }
}
