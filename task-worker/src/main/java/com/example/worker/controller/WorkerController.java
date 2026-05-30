package com.example.worker.controller;

import com.example.worker.service.DockerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/worker")
@RequiredArgsConstructor
public class WorkerController {

    private final DockerService dockerService;

    @PostMapping("/submit")
    public Map<String, Object> submit(@RequestBody Map<String, Object> payload) {
        try {
            String executorId = dockerService.submit(payload);
            return Map.of("code", 0, "message", "success",
                    "data", Map.of("executorId", executorId));
        } catch (Exception e) {
            log.error("Submit failed: {}", e.getMessage(), e);
            return Map.of("code", 1, "message", e.getMessage());
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestParam String executorId) {
        Map<String, Object> statusData = dockerService.queryStatus(executorId);
        return Map.of("code", 0, "message", "success", "data", statusData);
    }

    @PostMapping("/stop")
    public Map<String, Object> stop(@RequestBody Map<String, String> body) {
        String executorId = body.get("executorId");
        dockerService.stop(executorId);
        return Map.of("code", 0, "message", "success");
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "runningTasks", dockerService.getRunningCount());
    }
}
