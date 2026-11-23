package com.rb.control.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditController(@Value("${rb.audit.file}") String path) {
        this.file = Path.of(path);
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "100") int limit) throws IOException {
        if (!Files.exists(file)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = lines.size() - 1; i >= 0 && result.size() < limit; i--) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            Map<String, Object> obj = mapper.readValue(line,
                    new TypeReference<Map<String, Object>>() {});
            if (userId == null || userId.equals(obj.get("userId"))) {
                result.add(obj);
            }
        }
        return result;
    }
}