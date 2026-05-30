package com.example.orchestrator.engine;

import com.example.orchestrator.model.enums.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static com.example.orchestrator.model.enums.TaskStatus.*;

@Component
public class TaskStateMachine {
    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = Map.of(
            PENDING, Set.of(PREPARING, FAILED),
            PREPARING, Set.of(WAITING, FAILED),
            WAITING, Set.of(RUNNING, FAILED),
            RUNNING, Set.of(POST_PROCESSING, STOPPING, FAILED),
            POST_PROCESSING, Set.of(COMPLETED, FAILED),
            STOPPING, Set.of(STOPPED, FAILED)
    );

    public boolean canTransition(TaskStatus from, TaskStatus to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public Set<TaskStatus> terminalStatuses() {
        return Set.of(COMPLETED, FAILED, STOPPED);
    }
}
