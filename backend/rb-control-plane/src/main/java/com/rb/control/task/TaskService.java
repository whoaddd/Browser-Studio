package com.rb.control.task;

import com.google.gson.Gson;
import com.rb.control.audit.AuditService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class TaskService {

    private final TaskExecutor taskExecutor;
    private final AuditService auditService;
    private final Path dir;
    private final Gson gson = new Gson();
    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    public TaskService(TaskExecutor taskExecutor,
                       AuditService auditService,
                       @Value("${rb.task.dir:./data/tasks}") String dir) throws IOException {
        this.taskExecutor = taskExecutor;
        this.auditService = auditService;
        this.dir = Path.of(dir);
        Files.createDirectories(this.dir);
    }

    public Task submit(String userId, String url, String script) {
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Task task = new Task(taskId, userId, url, script);
        tasks.put(taskId, task);
        auditService.log("TASK_SUBMIT", userId, taskId, "url=" + url);

        pool.submit(() -> {
            try {
                taskExecutor.execute(task);
                saveResult(task);
            } catch (Exception e) {
                task.setStatus("FAILED");
                task.setError(e.getMessage());
                saveResult(task);
            }
        });
        return task;
    }

    public Task get(String taskId) { return tasks.get(taskId); }

    public String getResultFile(String taskId) {
        Path f = dir.resolve(taskId + ".json");
        if (!Files.exists(f)) return null;
        try { return Files.readString(f, StandardCharsets.UTF_8); }
        catch (IOException e) { return null; }
    }

    private void saveResult(Task task) {
        try {
            String json = gson.toJson(task);
            Files.writeString(dir.resolve(task.getTaskId() + ".json"), json, StandardCharsets.UTF_8);
            auditService.log("TASK_DONE", task.getUserId(), task.getTaskId(),
                    "status=" + task.getStatus());
        } catch (IOException e) {
            System.err.println("保存任务结果失败: " + e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}