package com.rb.data.api;

import com.rb.data.session.ActionLogger;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ActionLogger actionLogger;

    public ExportController(ActionLogger actionLogger) {
        this.actionLogger = actionLogger;
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<Resource> export(@PathVariable String sessionId) {
        Path file = actionLogger.getLogFile(sessionId);
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"session-" + sessionId + ".jsonl\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @GetMapping("/{sessionId}/count")
    public ResponseEntity<?> count(@PathVariable String sessionId) {
        return ResponseEntity.ok(java.util.Map.of(
                "sessionId", sessionId,
                "records", actionLogger.getRecordCount(sessionId)
        ));
    }
}