package com.roadguard.service;

import com.roadguard.domain.enums.IssueType;
import com.roadguard.domain.enums.Specialization;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
class TradeRoutingTest {

    @Autowired RequestService requests;

    @Test
    @DisplayName("a photo that reads as something else does not redirect the job")
    void theDriverDecidesTheTrade() {
        assertEquals(Specialization.FUEL,
                requests.tradeFor(IssueType.FUEL_EMPTY, Specialization.ENGINE),
                "running out of fuel must go to a fuel mechanic whatever the photo looked like");

        assertEquals(Specialization.TIRE,
                requests.tradeFor(IssueType.FLAT_TIRE, Specialization.BRAKES),
                "a flat tyre must go to a tyre mechanic");
    }

    @Test
    @DisplayName("the model names the trade when the driver did not")
    void theModelFillsTheGap() {
        assertEquals(Specialization.BATTERY,
                requests.tradeFor(IssueType.OTHER, Specialization.BATTERY),
                "the driver said other, so the photo is the only thing to go on");
    }

    @Test
    @DisplayName("agreeing changes nothing")
    void agreementIsUneventful() {
        assertEquals(Specialization.FUEL,
                requests.tradeFor(IssueType.FUEL_EMPTY, Specialization.FUEL));
    }

    @Test
    @DisplayName("a lockout stays a lockout")
    void lockoutIsNotReinterpreted() {
        assertEquals(Specialization.GENERAL,
                requests.tradeFor(IssueType.LOCKOUT, Specialization.ENGINE),
                "being locked out is not an engine fault however the car photographs");
    }

    @Test
    @DisplayName("no verdict from the model leaves the driver's choice standing")
    void noVerdictIsFine() {
        assertEquals(Specialization.BRAKES,
                requests.tradeFor(IssueType.BRAKES, null));

        assertEquals(Specialization.GENERAL,
                requests.tradeFor(IssueType.OTHER, null),
                "nothing to go on at all falls back to a general mechanic");
    }

    @Test
    @DisplayName("every issue the driver can pick routes somewhere, whatever the model says")
    void nothingIsLeftUnrouted() {
        for (IssueType issue : IssueType.values()) {
            Specialization trade = requests.tradeFor(issue, Specialization.ENGINE);
            org.junit.jupiter.api.Assertions.assertNotNull(trade, issue + " must route somewhere");
            if (issue != IssueType.OTHER) {
                assertEquals(issue.defaultSpecialization(), trade,
                        issue + " must follow the driver's own choice");
            }
        }
    }
}
