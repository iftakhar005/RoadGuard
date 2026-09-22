package com.roadguard.service;

import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.Severity;
import com.roadguard.domain.enums.Specialization;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class AiTriageTest {

    @Autowired AiTriageService triage;

    @Test
    @DisplayName("plain json is read as the model meant it")
    void readsPlainJson() {
        String answer = """
                {"faultCategory":"Punctured front tyre","specialization":"TIRE",
                 "severity":"LOW","confidence":0.82,
                 "driverGuidance":"Pull off the road.","likelyParts":["tyre","valve"]}""";

        AiTriageService.Diagnosis d = triage.parse(answer, IssueType.FLAT_TIRE);

        assertEquals(Specialization.TIRE, d.specialization());
        assertEquals(Severity.LOW, d.severity());
        assertEquals("Punctured front tyre", d.faultCategory());
        assertEquals("tyre, valve", d.likelyParts());
        assertFalse(d.fallback());
    }

    @Test
    @DisplayName("a fenced code block is unwrapped before reading")
    void readsFencedJson() {
        String answer = """
                ```json
                {"faultCategory":"Flat battery","specialization":"BATTERY","severity":"HIGH",
                 "confidence":0.5,"driverGuidance":"Stay inside.","likelyParts":[]}
                ```""";

        AiTriageService.Diagnosis d = triage.parse(answer, IssueType.DEAD_BATTERY);

        assertEquals(Specialization.BATTERY, d.specialization());
        assertEquals(Severity.HIGH, d.severity());
        assertFalse(d.fallback());
    }

    @Test
    @DisplayName("chatter around the json does not stop it being read")
    void readsJsonWithPreamble() {
        String answer = "Sure! Here is the triage:\n"
                + "{\"faultCategory\":\"Engine smoke\",\"specialization\":\"ENGINE\","
                + "\"severity\":\"CRITICAL\",\"confidence\":0.9,"
                + "\"driverGuidance\":\"Get away from the car.\",\"likelyParts\":[]}\n"
                + "Hope that helps.";

        AiTriageService.Diagnosis d = triage.parse(answer, IssueType.ENGINE);

        assertEquals(Specialization.ENGINE, d.specialization());
        assertEquals(Severity.CRITICAL, d.severity());
    }

    @Test
    @DisplayName("a truncated answer falls back instead of throwing")
    void garbledAnswerFallsBack() {
        AiTriageService.Diagnosis d = triage.parse("{\"specialization\":\"TIR", IssueType.FLAT_TIRE);

        assertTrue(d.fallback());
        assertEquals(Specialization.TIRE, d.specialization(), "falls back to the issue type");
        assertEquals(Severity.MEDIUM, d.severity());
        assertEquals(AiTriageService.SAFE_GUIDANCE, d.driverGuidance());
    }

    @Test
    @DisplayName("an empty answer falls back")
    void emptyAnswerFallsBack() {
        assertTrue(triage.parse("", IssueType.FLAT_TIRE).fallback());
        assertTrue(triage.parse(null, IssueType.FLAT_TIRE).fallback());
    }

    @Test
    @DisplayName("a specialization the enum does not know is refused, the rest is kept")
    void unknownSpecializationIsRefused() {
        String answer = """
                {"faultCategory":"Odd","specialization":"WARP_DRIVE","severity":"HIGH",
                 "confidence":0.4,"driverGuidance":"Wait safely.","likelyParts":[]}""";

        AiTriageService.Diagnosis d = triage.parse(answer, IssueType.FLAT_TIRE);

        assertEquals(Specialization.TIRE, d.specialization(), "falls back to the issue type");
        assertEquals(Severity.HIGH, d.severity(), "the usable field is still honoured");
        assertFalse(d.fallback(), "the answer parsed, only one field was unusable");
    }

    @Test
    @DisplayName("a severity the enum does not know is refused")
    void unknownSeverityIsRefused() {
        String answer = """
                {"faultCategory":"Odd","specialization":"BRAKES","severity":"APOCALYPTIC",
                 "confidence":0.4,"driverGuidance":"Wait safely.","likelyParts":[]}""";

        AiTriageService.Diagnosis d = triage.parse(answer, IssueType.BRAKES);

        assertEquals(Specialization.BRAKES, d.specialization());
        assertEquals(Severity.MEDIUM, d.severity());
    }

    @Test
    @DisplayName("lowercase enum names are accepted")
    void lowercaseEnumsAreAccepted() {
        String answer = """
                {"faultCategory":"Flat","specialization":"tire","severity":"low",
                 "confidence":0.7,"driverGuidance":"Wait safely.","likelyParts":[]}""";

        AiTriageService.Diagnosis d = triage.parse(answer, IssueType.FLAT_TIRE);

        assertEquals(Specialization.TIRE, d.specialization());
        assertEquals(Severity.LOW, d.severity());
    }

    @Test
    @DisplayName("missing guidance is replaced with something safe to read")
    void missingGuidanceIsReplaced() {
        String answer = """
                {"faultCategory":"Flat","specialization":"TIRE","severity":"LOW","confidence":0.7}""";

        AiTriageService.Diagnosis d = triage.parse(answer, IssueType.FLAT_TIRE);

        assertEquals(AiTriageService.SAFE_GUIDANCE, d.driverGuidance());
    }

    @Test
    @DisplayName("with no key configured the service never calls out")
    void stubProviderIsNotLive() {
        assertFalse(triage.isLive(), "tests must never reach the network");

        AiTriageService.Diagnosis d =
                triage.triage(new byte[]{1, 2, 3}, "image/jpeg", "note", IssueType.TOWING);

        assertTrue(d.fallback());
        assertEquals(Specialization.TOWING, d.specialization());
        assertEquals(Severity.MEDIUM, d.severity());
        assertNotNull(d.driverGuidance());
    }

    @Test
    @DisplayName("no photo at all still produces a usable answer")
    void noPhotoFallsBackToTheIssueType() {
        AiTriageService.Diagnosis d = triage.triage(null, null, null, IssueType.DEAD_BATTERY);

        assertTrue(d.fallback());
        assertEquals(Specialization.BATTERY, d.specialization());
    }
}
