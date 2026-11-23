package com.rb.control.api;

import com.rb.control.task.Task;
import com.rb.control.task.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ResponseEntity<?> submit(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestBody Map<String, String> body) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing X-User-Id"));
        }
        String url = body.get("url");
        String script = body.get("script");
        if (url == null || script == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing url or script"));
        }
        Task task = taskService.submit(userId, url, script);
        return ResponseEntity.ok(Map.of(
                "taskId", task.getTaskId(),
                "status", task.getStatus()
        ));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<?> query(@PathVariable String taskId) {
        Task task = taskService.get(taskId);
        if (task == null) {
            String file = taskService.getResultFile(taskId);
            if (file == null) return ResponseEntity.notFound().build();
            return ResponseEntity.ok(file);
        }
        return ResponseEntity.ok(Map.of(
                "taskId", task.getTaskId(),
                "status", task.getStatus(),
                "url", task.getUrl(),
                "result", task.getResult() == null ? "" : task.getResult(),
                "error", task.getError() == null ? "" : task.getError(),
                "createdAt", task.getCreatedAt(),
                "finishedAt", task.getFinishedAt()
        ));
    }
}