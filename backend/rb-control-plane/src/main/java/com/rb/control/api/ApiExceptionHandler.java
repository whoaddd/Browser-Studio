package com.rb.control.api;

import com.github.dockerjava.api.exception.DockerException;
import com.rb.control.container.ContainerManager;
import com.rb.control.session.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常出口。
 *
 * 旧版本没有全局异常处理，任何 Docker/镜像问题都会变成前端看到的 “创建失败: 500”，
 * 运维无法判断到底缺镜像、缺 socket 还是网络不通。现在按错误类型给出可执行的提示。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(SessionService.QuotaExceededException.class)
    public ResponseEntity<?> quota(SessionService.QuotaExceededException e) {
        return build(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED", e.getMessage());
    }

    @ExceptionHandler(ContainerManager.ImageUnavailableException.class)
    public ResponseEntity<?> imageUnavailable(ContainerManager.ImageUnavailableException e) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "CHROMIUM_IMAGE_UNAVAILABLE", e.getMessage());
    }

    @ExceptionHandler({DockerException.class, IllegalStateException.class})
    public ResponseEntity<?> browserStartFailed(RuntimeException e) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "BROWSER_START_FAILED", e.getMessage());
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<?> requestError(org.springframework.web.server.ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", e.getReason()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> unexpected(Exception e) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", e.getMessage());
    }

    private ResponseEntity<?> build(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("code", code);
        body.put("error", message == null ? status.getReasonPhrase() : shorten(message));
        return ResponseEntity.status(status).body(body);
    }

    private String shorten(String message) {
        String m = message.replaceAll("\\s+", " ").trim();
        return m.length() > 500 ? m.substring(0, 500) + "..." : m;
    }
}
