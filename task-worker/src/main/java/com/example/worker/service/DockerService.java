package com.example.worker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerService {

    private final ObjectMapper objectMapper;
    private final S3Service s3Service;

    @Value("${executor.docker.shm-size:8g}")
    private String defaultShmSize;

    @Value("${executor.docker.network:}")
    private String defaultNetwork;

    private final ConcurrentHashMap<String, String> runningContainers = new ConcurrentHashMap<>();

    public String submit(Map<String, Object> payload) throws Exception {
        String taskIdentity = (String) payload.get("taskIdentity");
        String image = (String) payload.get("image");
        String containerName = "task-" + taskIdentity;

        downloadInputs(payload);

        List<String> cmd = buildDockerRunCommand(payload, containerName);
        log.info("[{}] Docker command: {}", taskIdentity, String.join(" ", cmd));

        exec("docker", "rm", "-f", containerName);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        asyncReadOutput(taskIdentity, process);

        Thread.sleep(2000);
        if (!isRunning(containerName)) {
            int exitCode = process.waitFor(10, TimeUnit.SECONDS) ? process.exitValue() : -1;
            throw new RuntimeException("Container failed to start, exit code: " + exitCode);
        }

        runningContainers.put(containerName, taskIdentity);
        return containerName;
    }

    public Map<String, Object> queryStatus(String containerName) {
        String output = execOutput("docker", "inspect", "--format",
                "{{.State.Status}}|{{.State.ExitCode}}", containerName);

        Map<String, Object> result = new HashMap<>();
        if (output == null || output.isBlank()) {
            result.put("status", "not_found");
            result.put("exitCode", -1);
            return result;
        }

        String[] parts = output.trim().split("\\|");
        result.put("status", parts[0]);
        result.put("exitCode", parts.length > 1 ? Integer.parseInt(parts[1].trim()) : -1);

        if ("exited".equals(parts[0])) {
            runningContainers.remove(containerName);
            exec("docker", "rm", "-f", containerName);
        }
        return result;
    }

    public void stop(String containerName) {
        exec("docker", "stop", "-t", "30", containerName);
        exec("docker", "rm", "-f", containerName);
        runningContainers.remove(containerName);
    }

    public int getRunningCount() {
        return runningContainers.size();
    }

    @SuppressWarnings("unchecked")
    private List<String> buildDockerRunCommand(Map<String, Object> payload, String containerName) throws Exception {
        String image = (String) payload.get("image");
        String jobConfigStr = (String) payload.getOrDefault("jobConfig", "{}");
        Map<String, Object> jobConfig = objectMapper.readValue(jobConfigStr, new TypeReference<>() {});

        String shmSize = getStr(jobConfig, "shmSize", defaultShmSize);
        String network = getStr(jobConfig, "network", defaultNetwork);

        List<String> cmd = new ArrayList<>(List.of("docker", "run", "--name", containerName, "--rm", "--shm-size=" + shmSize));

        if (network != null && !network.isBlank()) cmd.addAll(List.of("--network", network));

        int gpuCount = getGpuCount((String) payload.getOrDefault("resourceConfig", "{}"));
        if (gpuCount > 0) cmd.addAll(List.of("--gpus", String.valueOf(gpuCount)));

        String envConfigStr = (String) payload.getOrDefault("envConfig", "[]");
        List<Map<String, String>> envVars = objectMapper.readValue(envConfigStr, new TypeReference<>() {});
        for (Map<String, String> env : envVars) {
            String name = env.getOrDefault("Name", env.getOrDefault("name", ""));
            String value = env.getOrDefault("Value", env.getOrDefault("value", ""));
            if (!name.isBlank()) cmd.addAll(List.of("-e", name + "=" + value));
        }

        String taskIdentity = (String) payload.get("taskIdentity");
        Path tempDir = Files.createTempDirectory("task-" + taskIdentity);
        Files.writeString(tempDir.resolve("io_config.json"),
                (String) payload.getOrDefault("ioConfig", "{}"), StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("hyperparameters.json"),
                (String) payload.getOrDefault("inputDetail", "{}"), StandardCharsets.UTF_8);
        cmd.addAll(List.of("-v", tempDir + ":/opt/ml/input/config:ro"));

        Object volumes = jobConfig.get("volumes");
        if (volumes instanceof List<?> volumeList) {
            for (Object v : volumeList) {
                if (v instanceof String vs && !vs.isBlank()) cmd.addAll(List.of("-v", vs));
            }
        }

        cmd.add(image);

        Object command = jobConfig.get("command");
        if (command instanceof List<?> cmdList) {
            for (Object c : cmdList) { if (c != null) cmd.add(c.toString()); }
        } else if (command instanceof String cs && !cs.isBlank()) {
            cmd.addAll(List.of(cs.split("\\s+")));
        }

        return cmd;
    }

    @SuppressWarnings("unchecked")
    private void downloadInputs(Map<String, Object> payload) {
        try {
            String ioConfigStr = (String) payload.getOrDefault("ioConfig", "{}");
            Map<String, Object> ioConfig = objectMapper.readValue(ioConfigStr, new TypeReference<>() {});
            Object inputs = ioConfig.get("inputs");
            if (inputs instanceof List<?> inputList) {
                for (Object item : inputList) {
                    if (item instanceof Map<?, ?> input) {
                        String s3Path = (String) input.get("s3Path");
                        String localPath = (String) input.get("localPath");
                        if (s3Path != null && localPath != null && s3Path.startsWith("s3://")) {
                            s3Service.download(s3Path, localPath);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to download inputs: {}", e.getMessage());
        }
    }

    private int getGpuCount(String resourceConfig) {
        try {
            Map<String, Object> r = objectMapper.readValue(resourceConfig, new TypeReference<>() {});
            Object gpu = r.get("Gpu");
            if (gpu == null) gpu = r.get("gpu");
            if (gpu instanceof Number n) {
                int v = n.intValue();
                return Math.max(0, v >= 100 ? v / 100 : (v > 0 ? 1 : 0));
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private String getStr(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return (v instanceof String s && !s.isBlank()) ? s : def;
    }

    private boolean isRunning(String name) {
        String out = execOutput("docker", "inspect", "--format", "{{.State.Status}}", name);
        return out != null && out.trim().equals("running");
    }

    private void asyncReadOutput(String identity, Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.info("[docker-{}] {}", identity, line);
                }
            } catch (IOException ignored) {}
        }, "docker-log-" + identity);
        thread.start();
    }

    private void exec(String... cmd) {
        try { new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor(30, TimeUnit.SECONDS); }
        catch (Exception ignored) {}
    }

    private String execOutput(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(10, TimeUnit.SECONDS);
            return out;
        } catch (Exception e) { return null; }
    }
}
