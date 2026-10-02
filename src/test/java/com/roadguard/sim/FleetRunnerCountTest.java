package com.roadguard.sim;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FleetRunnerCountTest {

    @Test
    @DisplayName("winners are counted for each job, not added up across the whole run")
    void winnersAreCountedPerJob() {
        FleetRunner runner = new FleetRunner();

        for (int i = 0; i < 10; i++) {
            runner.toldTheyWon(101L);
        }
        runner.toldTheyWon(102L);

        FleetRunner.FleetState state = runner.snapshot(10);

        assertEquals(102L, state.lastJobId(), "the latest job should be the one reported");
        assertEquals(1, state.lastJobWinners(), "only one mechanic was told they won the latest job");
        assertEquals(10, runner.mostWinnersOnOneJob(), "the worst job had ten winners, not eleven");
    }

    @Test
    @DisplayName("nothing has been won before the first job")
    void nothingYet() {
        FleetRunner.FleetState state = new FleetRunner().snapshot(3);

        assertNull(state.lastJobId());
        assertEquals(0, state.lastJobWinners());
    }
}
