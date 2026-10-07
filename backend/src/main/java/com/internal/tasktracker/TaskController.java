package com.internal.tasktracker;

import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@CrossOrigin(origins = "http://localhost:5173")
public class TaskController {

    private final TaskRepository taskRepository;

    public TaskController(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @GetMapping("/api/tasks")
    public ResponseEntity<?> searchTasks(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "10") int pageSize) {

        String query = q == null ? "" : q.trim();

        String normalizedStatus = null;
        if (status != null && !status.trim().isEmpty()) {
            try {
                normalizedStatus = TaskStatus.valueOf(status.trim().toUpperCase()).name();
            } catch (IllegalArgumentException e) {
                normalizedStatus = status.trim().toUpperCase();
            }
        }

        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);

        System.out.println("[TaskController] q=\"" + query + "\" status=" + normalizedStatus
            + " page=" + safePage + " pageSize=" + safePageSize);

        Page<Task> results = taskRepository.searchTasks(
            query,
            normalizedStatus,
            PageRequest.of(safePage - 1, safePageSize, Sort.by(Sort.Direction.ASC, "id")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", results.getContent());
        response.put("total", results.getTotalElements());
        response.put("page", safePage);
        response.put("pageSize", safePageSize);

        return ResponseEntity.ok(response);
    }
}
