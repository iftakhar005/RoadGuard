package com.roadguard.service;

import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Map;

public final class UploadRules {

    public static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp");

    private UploadRules() {
    }

    public static String extensionFor(MultipartFile file) {
        String declared = file.getContentType();
        if (declared != null) {
            String known = ALLOWED_TYPES.get(declared.toLowerCase());
            if (known != null) {
                return known;
            }
        }

        String name = file.getOriginalFilename();
        if (name != null) {
            String lower = name.toLowerCase();
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                return ".jpg";
            }
            if (lower.endsWith(".png")) {
                return ".png";
            }
            if (lower.endsWith(".webp")) {
                return ".webp";
            }
        }
        throw new IllegalArgumentException("Only JPG, PNG or WEBP pictures are accepted");
    }

    public static void check(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose a picture to upload");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("That picture is larger than 5 MB");
        }
        extensionFor(file);
    }

    public static Path within(Path directory, String name) {
        Path resolved = directory.resolve(name).normalize();
        if (!resolved.startsWith(directory)) {
            throw new IllegalArgumentException("That path is not allowed");
        }
        return resolved;
    }
}
