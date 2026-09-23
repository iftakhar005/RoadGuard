package com.roadguard.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.Severity;
import com.roadguard.domain.enums.Specialization;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiTriageService {

    static final String SAFE_GUIDANCE = "Stay in a safe location away from traffic.";

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    private static final String INSTRUCTION = """
            You are a vehicle roadside-fault triage assistant. Look at the image and \
            the driver's note and return ONLY valid JSON (no markdown), exactly: \
            {"faultCategory":"short label",\
            "specialization":"TIRE|BATTERY|ENGINE|ELECTRICAL|BRAKES|FUEL|TOWING|GENERAL",\
            "severity":"LOW|MEDIUM|HIGH|CRITICAL",\
            "confidence":0.0,\
            "driverGuidance":"one or two short safety tips while waiting",\
            "likelyParts":["..."]}. Driver note: %s.""";

    private final ObjectMapper mapper;

    @Value("${app.ai.provider:stub}")
    private String provider;

    @Value("${app.gemini.model:gemini-2.5-flash}")
    private String model;

    @Value("${app.gemini.api-key:}")
    private String apiKey;

    @Value("${app.ai.timeout-sec:15}")
    private long timeoutSeconds;

    @Value("${app.ai.max-attempts:3}")
    private int maxAttempts;

    @Value("${app.ai.retry-delay-ms:800}")
    private long retryDelayMs;

    public record Diagnosis(
            String faultCategory,
            Specialization specialization,
            Severity severity,
            double confidence,
            String driverGuidance,
            String likelyParts,
            boolean fallback) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Wire(
            String faultCategory,
            String specialization,
            String severity,
            Double confidence,
            String driverGuidance,
            List<String> likelyParts) {
    }

    public boolean isLive() {
        return "gemini".equalsIgnoreCase(provider) && apiKey != null && !apiKey.isBlank();
    }

    public Diagnosis triage(byte[] image, String mimeType, String note, IssueType issueType) {
        if (!isLive() || image == null || image.length == 0) {
            return fallbackFor(issueType);
        }
        try {
            String answer = ask(image, mimeType, note);
            return parse(answer, issueType);
        } catch (Exception e) {
            log.warn("Triage failed, using the safe default: {}", e.toString());
            return fallbackFor(issueType);
        }
    }

    public Diagnosis fallbackFor(IssueType issueType) {
        Specialization needed = issueType == null
                ? Specialization.GENERAL
                : issueType.defaultSpecialization();

        return new Diagnosis(
                issueType == null ? "Unknown" : issueType.name(),
                needed == null ? Specialization.GENERAL : needed,
                Severity.MEDIUM,
                0.0,
                SAFE_GUIDANCE,
                null,
                true);
    }

    private String ask(byte[] image, String mimeType, String note) throws Exception {
        String payload = mapper.writeValueAsString(java.util.Map.of(
                "contents", List.of(java.util.Map.of(
                        "parts", List.of(
                                java.util.Map.of("inline_data", java.util.Map.of(
                                        "mime_type", mimeType == null ? "image/jpeg" : mimeType,
                                        "data", Base64.getEncoder().encodeToString(image))),
                                java.util.Map.of("text",
                                        INSTRUCTION.formatted(note == null ? "none" : note)))))));

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 10)))
                .build();

        HttpRequest call = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT.formatted(model)))
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = sendWithRetries(client, call);
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Gemini replied " + response.statusCode()
                    + ": " + clip(response.body(), 300));
        }

        JsonNode text = mapper.readTree(response.body())
                .path("candidates").path(0)
                .path("content").path("parts").path(0)
                .path("text");

        if (text.isMissingNode() || text.asText().isBlank()) {
            throw new IllegalStateException("Gemini returned no text");
        }
        return text.asText();
    }

    private HttpResponse<String> sendWithRetries(HttpClient client, HttpRequest call) throws Exception {
        int attempts = Math.max(1, maxAttempts);
        HttpResponse<String> last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            last = client.send(call, HttpResponse.BodyHandlers.ofString());
            if (!shouldRetry(last.statusCode()) || attempt == attempts) {
                return last;
            }

            long pause = retryDelayMs * attempt;
            log.info("Gemini replied {}, retrying triage in {} ms ({}/{})",
                    last.statusCode(), pause, attempt + 1, attempts);
            Thread.sleep(pause);
        }

        return last;
    }

    private static boolean shouldRetry(int status) {
        return status == 429 || status >= 500;
    }

    Diagnosis parse(String answer, IssueType issueType) {
        Diagnosis safe = fallbackFor(issueType);
        try {
            Wire wire = mapper.readValue(unfence(answer), Wire.class);

            Specialization specialization = readEnum(
                    Specialization.class, wire.specialization(), safe.specialization());
            Severity severity = readEnum(
                    Severity.class, wire.severity(), safe.severity());

            String guidance = blankToNull(wire.driverGuidance());
            String parts = wire.likelyParts() == null || wire.likelyParts().isEmpty()
                    ? null
                    : String.join(", ", wire.likelyParts());

            return new Diagnosis(
                    blankToNull(wire.faultCategory()),
                    specialization,
                    severity,
                    wire.confidence() == null ? 0.0 : wire.confidence(),
                    guidance == null ? SAFE_GUIDANCE : guidance,
                    clip(parts, 500),
                    false);

        } catch (Exception e) {
            log.warn("Could not read the triage answer, using the safe default: {}", e.toString());
            return safe;
        }
    }

    static String unfence(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            if (firstBreak > -1) {
                text = text.substring(firstBreak + 1);
            }
            int lastFence = text.lastIndexOf("```");
            if (lastFence > -1) {
                text = text.substring(0, lastFence);
            }
        }
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open > -1 && close > open) {
            text = text.substring(open, close + 1);
        }
        return text.trim();
    }

    private static <E extends Enum<E>> E readEnum(Class<E> type, String value, E whenUnusable) {
        if (value == null || value.isBlank()) {
            return whenUnusable;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return whenUnusable;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
