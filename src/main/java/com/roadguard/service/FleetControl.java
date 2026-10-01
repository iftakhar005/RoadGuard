package com.roadguard.service;

import com.roadguard.sim.AccountSource;
import com.roadguard.sim.DirectAccountSource;
import com.roadguard.sim.Fleet;
import com.roadguard.sim.FleetRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class FleetControl {

    private final AuthService authService;
    private final com.roadguard.tcp.TcpGateway tcpGateway;
    private final FleetRunner runner = new FleetRunner();

    @Value("${app.tcp.port:9090}")
    private int gatewayPort;

    @Value("${app.dispatch.offer-count:3}")
    private int offerCount;

    public synchronized FleetRunner.FleetState status() {
        return runner.snapshot(offerCount);
    }

    public synchronized FleetRunner.FleetState start(Integer count) {
        int target = count != null && count > 0 ? Math.min(count, 25) : 8;
        if (!runner.isRunning()) {
            try {
                int port = tcpGateway != null && tcpGateway.port() > 0 ? tcpGateway.port() : gatewayPort;
                Fleet options = Fleet.defaults(target, port);
                AccountSource accountSource = new DirectAccountSource(authService);
                runner.start(options, accountSource);
                log.info("Fleet started with {} mechanics on port {}", target, port);
            } catch (Exception e) {
                log.warn("Could not start fleet: {}", e.toString());
                throw new IllegalStateException("Could not start fleet: " + e.getMessage());
            }
        }
        return runner.snapshot(offerCount);
    }

    public synchronized FleetRunner.FleetState stop() {
        if (runner.isRunning()) {
            runner.stop();
            log.info("Fleet stopped");
        }
        return runner.snapshot(offerCount);
    }

    public synchronized FleetRunner.FleetState setRace(boolean armed) {
        runner.setRace(armed);
        log.info("Fleet race armed: {}", armed);
        return runner.snapshot(offerCount);
    }

    public synchronized FleetRunner.FleetState dropWinner() {
        runner.dropWinner();
        log.info("Fleet winner dropped");
        return runner.snapshot(offerCount);
    }

    public boolean isRunning() {
        return runner.isRunning();
    }
}
