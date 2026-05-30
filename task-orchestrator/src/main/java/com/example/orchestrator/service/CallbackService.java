package com.example.orchestrator.service;

import com.example.orchestrator.mapper.TaskMapper;
import com.example.orchestrator.model.entity.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallbackService {

    private final TaskMapper taskMapper;
    private final RestTemplate restTemplate;

    public void doCallback(Task task) {
        if (task.getCallbackUrl() == null || task.getCallbackUrl().isBlank()) return;
        if (Boolean.TRUE.equals(task.getCallbackDone())) return;

        try {
            Map<String, Object> body = Map.of(
                    "identity", task.getIdentity(),
                    "typeCode", task.getTypeCode(),
                    "status", task.getStatus().name(),
                    "progress", task.getProgress(),
                    "failReason", task.getFailReason() != null ? task.getFailReason() : "");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> resp = restTemplate.exchange(
                    task.getCallbackUrl(), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

            if (resp.getStatusCode().is2xxSuccessful()) {
                task.setCallbackDone(true);
                task.setUpdateTime(Instant.now());
                taskMapper.updateById(task);
                log.info("[{}] Callback success", task.getIdentity());
            }
        } catch (Exception e) {
            log.error("[{}] Callback failed: {}", task.getIdentity(), e.getMessage());
        }
    }
}
