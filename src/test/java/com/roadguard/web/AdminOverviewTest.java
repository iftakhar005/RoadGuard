package com.roadguard.web;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.domain.enums.Specialization;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.UserRepository;
import com.roadguard.sim.AccountSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminOverviewTest {

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired MechanicProfileRepository mechanics;
    @Autowired TransactionTemplate tx;

    private String mechanic(String name, Double lat, Double lng) {
        tx.executeWithoutResult(s -> {
            User user = users.save(new User(name, name + "@test.com", "x", Role.MECHANIC));
            MechanicProfile profile = new MechanicProfile(user);
            profile.setSpecializations(Set.of(Specialization.TIRE));
            profile.setStatus(AvailabilityStatus.OFFLINE);
            profile.setCurrentLat(lat);
            profile.setCurrentLng(lng);
            mechanics.save(profile);
        });
        return name;
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("a mechanic with no position is named as missing, not silently left off the map")
    void mechanicWithoutPositionIsReported() throws Exception {
        String lost = mechanic("nopos_" + UNIQUE.incrementAndGet(), null, null);
        String placed = mechanic("haspos_" + UNIQUE.incrementAndGet(), 23.81, 90.41);

        mockMvc.perform(get("/api/admin/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocatedMechanics", hasItem(lost)))
                .andExpect(jsonPath("$.unlocatedMechanics", not(hasItem(placed))))
                .andExpect(jsonPath("$.mechanicLocations[*].username", hasItem(placed)))
                .andExpect(jsonPath("$.mechanicLocations[*].username", not(hasItem(lost))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("a leftover simulated mechanic is not reported as a problem")
    void simulatedMechanicsAreNotNagged() throws Exception {
        String sim = mechanic(AccountSource.SIM_PREFIX + "900" + UNIQUE.incrementAndGet(), null, null);

        mockMvc.perform(get("/api/admin/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unlocatedMechanics", not(hasItem(sim))));
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    @DisplayName("a driver cannot read the admin overview")
    void driverIsTurnedAway() throws Exception {
        mockMvc.perform(get("/api/admin/overview")).andExpect(status().isForbidden());
    }
}
