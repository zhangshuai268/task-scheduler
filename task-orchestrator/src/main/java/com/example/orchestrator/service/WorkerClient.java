package com.example.orchestrator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public String submitTask(String workerUrl, Map<String, Object> payload) throws Exception {
        String url = workerUrl + "/api/v1/worker/submit";
        Map<String, Object> resp = post(url, payload);
        if (resp == null || !Integer.valueOf(0).equals(resp.get("code"))) {
            String msg = resp != null ? String.valueOf(resp.get("message")) : "No response";
            throw new RuntimeException("Worker submit failed: " + msg);
        }
        Object data = resp.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            return String.valueOf(dataMap.get("executorId"));
        }
        return String.valueOf(data);
    }

    public Map<String, Object> queryStatus(String workerUrl, String executorId) throws Exception {
        String url = workerUrl + "/api/v1/worker/status?executorId=" + executorId;
        Map<String, Object> resp = get(url);
        Object data = resp.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            return (Map<String, Object>) dataMap;
        }
        return resp;
    }

    public void stopTask(String workerUrl, String executorId) throws Exception {
        String url = workerUrl + "/api/v1/worker/stop";
        post(url, Map.of("executorId", executorId));
    }

    private Map<String, Object> post(String url, Object body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        return objectMapper.readValue(resp.getBody(), new TypeReference<>() {});
    }

    private Map<String, Object> get(String url) throws Exception {
        ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
        return objectMapper.readValue(resp.getBody(), new TypeReference<>() {});
    }
}
