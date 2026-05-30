package com.example.orchestrator.controller;

import com.example.orchestrator.model.dto.ApiResult;
import com.example.orchestrator.model.dto.TaskCreateRequest;
import com.example.orchestrator.model.dto.TaskResponse;
import com.example.orchestrator.model.entity.Task;
import com.example.orchestrator.service.TaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    @PostMapping
    public ApiResult<TaskResponse> create(@RequestBody @Valid TaskCreateRequest request) {
        Task task = taskService.createTask(request);
        return ApiResult.ok(TaskResponse.from(task));
    }

    @GetMapping("/{identity}")
    public ApiResult<TaskResponse> detail(@PathVariable String identity) {
        return ApiResult.ok(TaskResponse.from(taskService.getTask(identity)));
    }

    @GetMapping
    public ApiResult<List<TaskResponse>> list() {
        return ApiResult.ok(taskService.listActiveTasks().stream().map(TaskResponse::from).toList());
    }

    @PostMapping("/{identity}/stop")
    public ApiResult<Void> stop(@PathVariable String identity) {
        taskService.stopTask(identity);
        return ApiResult.ok();
    }
}
