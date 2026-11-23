package com.rb.control.task;

public class Task {
    private String taskId;
    private String userId;
    private String url;
    private String script;
    private String status;      // PENDING / RUNNING / DONE / FAILED
    private String result;      // JSON 字符串
    private String error;
    private long createdAt;
    private long finishedAt;

    public Task(String taskId, String userId, String url, String script) {
        this.taskId = taskId;
        this.userId = userId;
        this.url = url;
        this.script = script;
        this.status = "PENDING";
        this.createdAt = System.currentTimeMillis();
    }

    public String getTaskId() { return taskId; }
    public String getUserId() { return userId; }
    public String getUrl() { return url; }
    public String getScript() { return script; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public long getCreatedAt() { return createdAt; }
    public long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(long finishedAt) { this.finishedAt = finishedAt; }
}