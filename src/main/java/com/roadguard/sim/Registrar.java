package com.roadguard.sim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signs the pretend mechanics up over the ordinary REST API so the gateway has real
 * accounts and real tokens to check. Nothing here is a shortcut: the simulator gets
 * in the same way a person would, and the gateway refuses it otherwise.
 */
class Registrar implements AccountSource {

    /* every skill, so a simulated mechanic is a candidate for whatever is reported
       and the ranking never quietly leaves the whole fleet out */
    private static final List<String> EVERY_SKILL = List.of(
            "TIRE", "BATTERY", "ENGINE", "ELECTRICAL", "BRAKES", "FUEL", "TOWING", "GENERAL");

    private final Fleet options;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    Registrar(Fleet options) {
        this.options = options;
    }

    @Override
    public Account getAccount(int index) throws Exception {
        return signUp(index);
    }

    Account signUp(int index) throws Exception {
        String username = SIM_PREFIX + index;
        String email = SIM_PREFIX + index + "@fleet.local";

        Account existing = tryLogin(email);
        if (existing != null) {
            return existing;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("email", email);
        body.put("password", PASSWORD);
        body.put("role", "MECHANIC");
        body.put("phone", "0170000%04d".formatted(index));
        body.put("specializations", EVERY_SKILL);

        JsonNode answer = post("/api/auth/register", body);
        if (answer == null || !answer.hasNonNull("token")) {
            /* somebody else may have taken the name between the two calls */
            Account second = tryLogin(email);
            if (second != null) {
                return second;
            }
            throw new IllegalStateException("Could not sign up " + username);
        }
        return read(answer);
    }

    private Account tryLogin(String email) throws Exception {
        JsonNode answer = post("/api/auth/login",
                Map.of("email", email, "password", PASSWORD));

        return answer != null && answer.hasNonNull("token") ? read(answer) : null;
    }

    private Account read(JsonNode node) {
        return new Account(
                node.get("userId").asLong(),
                node.get("username").asText(),
                node.get("token").asText());
    }

    private JsonNode post(String path, Map<String, Object> body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(options.api() + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            return null;
        }
        return json.readTree(response.body());
    }
}
