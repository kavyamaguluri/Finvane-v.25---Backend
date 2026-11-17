package com.finvane.controller.common;

import com.finvane.model.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
@RestController
@RequestMapping({"/api/v1/attachments", "/attachments"})
@RequiredArgsConstructor
public class AttachmentController {

    private final JdbcTemplate jdbcTemplate;
    private static final String UPLOAD_DIR = "data/uploads";

    @PostMapping("/upload")
    @Transactional
    public ApiResponse<Map<String, Object>> uploadAttachment(
            @RequestParam("file") MultipartFile file,
            @RequestParam("entity_type") String entityType,
            @RequestParam("entity_id") Long entityId) throws IOException {

        if (file.isEmpty()) return ApiResponse.error("No file was uploaded.");

        Path uploadPath = Paths.get(UPLOAD_DIR);
        if (!Files.exists(uploadPath)) Files.createDirectories(uploadPath);

        String originalName = file.getOriginalFilename();
        String storedName = UUID.randomUUID().toString() + "_" + (originalName != null ? originalName : "file");
        Path filePath = uploadPath.resolve(storedName);
        Files.copy(file.getInputStream(), filePath);

        jdbcTemplate.update(
                "INSERT INTO attachments (entity_type, entity_id, filename, stored_name, mime_type, size_bytes, uploaded_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, 1)",
                entityType, entityId, originalName, storedName, file.getContentType(), file.getSize());

        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbcTemplate.update(
                "INSERT INTO audit_log (entity_type, entity_id, action, changed_by, detail) VALUES ('attachment', ?, 'upload:attachment', 1, ?)",
                id, "filename=" + originalName + " entity=" + entityType + ":" + entityId);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", id);
        res.put("filename", originalName);
        return ApiResponse.ok("Attachment uploaded successfully.", res);
    }

    @GetMapping("/{id}/download")
    public void downloadAttachment(@PathVariable Long id, HttpServletResponse response) throws IOException {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT * FROM attachments WHERE id = ? LIMIT 1", id);
        if (list.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Attachment not found.");
            return;
        }

        Map<String, Object> att = list.get(0);
        String storedName = (String) att.get("stored_name");
        String filename = (String) att.get("filename");
        String mimeType = (String) att.get("mime_type");

        Path filePath = Paths.get(UPLOAD_DIR, storedName);
        if (!Files.exists(filePath)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "File is missing on disk.");
            return;
        }

        response.setContentType(mimeType != null ? mimeType : "application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        Files.copy(filePath, response.getOutputStream());
    }

    @PostMapping("/{id}/delete")
    @Transactional
    public ApiResponse<String> deleteAttachment(@PathVariable Long id) {
        List<Map<String, Object>> list = jdbcTemplate.queryForList(
                "SELECT * FROM attachments WHERE id = ? LIMIT 1", id);
        if (list.isEmpty()) return ApiResponse.error("Attachment not found.");

        Map<String, Object> att = list.get(0);
        String storedName = (String) att.get("stored_name");
        Path filePath = Paths.get(UPLOAD_DIR, storedName);
        try { Files.deleteIfExists(filePath); } catch (Exception ignored) {}

        jdbcTemplate.update("DELETE FROM attachments WHERE id = ?", id);
        jdbcTemplate.update(
                "INSERT INTO audit_log (entity_type, entity_id, action, changed_by, detail) VALUES ('attachment', ?, 'delete:attachment', 1, 'deleted')", id);

        return ApiResponse.ok("Attachment deleted successfully.");
    }
}

