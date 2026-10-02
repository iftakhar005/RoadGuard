package com.roadguard.web;

import com.roadguard.domain.MechanicProfile;
import com.roadguard.domain.User;
import com.roadguard.domain.enums.AvailabilityStatus;
import com.roadguard.domain.enums.Role;
import com.roadguard.domain.enums.Specialization;
import com.roadguard.repository.MechanicProfileRepository;
import com.roadguard.repository.UserRepository;
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
class NearbyMechanicTest {

    /* a corner of the map nothing else in the suite uses */
    private static final double HERE_LAT = -30.0;
    private static final double HERE_LNG = 150.0;

    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired MechanicProfileRepository mechanics;
    @Autowired TransactionTemplate tx;

    private String mechanic(String prefix, AvailabilityStatus status, double lat, double lng) {
        String name = prefix + "_" + UNIQUE.incrementAndGet();
        tx.executeWithoutResult(s -> {
            User user = users.save(new User(name, name + "@test.com", "x", Role.MECHANIC));
            MechanicProfile profile = new MechanicProfile(user);
            profile.setSpecializations(Set.of(Specialization.TIRE));
            profile.setStatus(status);
            profile.setCurrentLat(lat);
            profile.setCurrentLng(lng);
            profile.setContactPhone("01700000000");
            mechanics.save(profile);
        });
        return name;
    }

    private String url() {
        return "/api/mechanics/nearby?lat=" + HERE_LAT + "&lng=" + HERE_LNG + "&radiusKm=5";
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    @DisplayName("only mechanics who are online and close by are listed, nearest first")
    void onlyOnlineAndNearby() throws Exception {
        String near = mechanic("near", AvailabilityStatus.ONLINE, HERE_LAT + 0.002, HERE_LNG);
        String nearer = mechanic("nearer", AvailabilityStatus.ONLINE, HERE_LAT + 0.0005, HERE_LNG);
        String offline = mechanic("offline", AvailabilityStatus.OFFLINE, HERE_LAT + 0.001, HERE_LNG);
        String busy = mechanic("busy", AvailabilityStatus.BUSY, HERE_LAT + 0.001, HERE_LNG);
        String far = mechanic("far", AvailabilityStatus.ONLINE, HERE_LAT + 0.5, HERE_LNG);

        mockMvc.perform(get(url()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", hasItem(near)))
                .andExpect(jsonPath("$[*].name", hasItem(nearer)))
                .andExpect(jsonPath("$[*].name", not(hasItem(offline))))
                .andExpect(jsonPath("$[*].name", not(hasItem(busy))))
                .andExpect(jsonPath("$[*].name", not(hasItem(far))))
                .andExpect(jsonPath("$[0].name").value(nearer))
                .andExpect(jsonPath("$[1].name").value(near));
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    @DisplayName("a driver is not handed a mechanic's account id or phone number")
    void nothingPrivateIsShared() throws Exception {
        mechanic("private", AvailabilityStatus.ONLINE, HERE_LAT + 0.0001, HERE_LNG);

        mockMvc.perform(get(url()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].distanceKm").exists())
                .andExpect(jsonPath("$[0].userId").doesNotExist())
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[0].contactPhone").doesNotExist())
                .andExpect(jsonPath("$[0].phone").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "DRIVER")
    @DisplayName("coordinates off the map are refused")
    void badCoordinatesAreRefused() throws Exception {
        mockMvc.perform(get("/api/mechanics/nearby?lat=95&lng=10")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/mechanics/nearby?lat=10&lng=200")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("nobody who is signed out can see where the mechanics are")
    void signedOutIsTurnedAway() throws Exception {
        mockMvc.perform(get(url())).andExpect(status().isUnauthorized());
    }
}
