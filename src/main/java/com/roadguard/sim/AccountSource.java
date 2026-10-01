package com.roadguard.sim;

public interface AccountSource {

    String SIM_PREFIX = "sim_mech_";
    String PASSWORD = "fleet-sim-pass";

    record Account(Long userId, String username, String token) {
    }

    Account getAccount(int index) throws Exception;
}
