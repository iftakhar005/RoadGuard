package com.roadguard.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Says out loud which database the application actually opened, and refuses to
 * run on the throwaway one by accident.
 *
 * <p>The properties file names H2 and the local file names MySQL, so a missing or
 * unread local file leaves the application talking to an empty H2 file instead of
 * the real data, with nothing in the log to say so. Every account appears to have
 * vanished and password recovery reports that no such person exists, which is a
 * long way from the actual cause.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseCheck {

    private final DataSource dataSource;
    private final Environment environment;

    @Value("${app.db.allow-throwaway:false}")
    private boolean allowThrowaway;

    @PostConstruct
    public void announce() {
        String url;
        String product;

        try (Connection connection = dataSource.getConnection()) {
            url = String.valueOf(connection.getMetaData().getURL());
            product = connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            throw new IllegalStateException("The database could not be reached: " + e.getMessage(), e);
        }

        boolean testing = Arrays.asList(environment.getActiveProfiles()).contains("test");
        boolean throwaway = url.toLowerCase().contains("jdbc:h2");

        if (throwaway && !testing && !allowThrowaway) {
            throw new IllegalStateException("""

                    ------------------------------------------------------------------
                     RoadGuard opened a throwaway database, not the real one.

                     Opened : %s

                     Your accounts and requests are not in there, so nobody will be
                     able to sign in and password recovery will say the account does
                     not exist.

                     This happens when application-local.properties is missing or was
                     not read. Copy application-local.properties.example to
                     application-local.properties and fill in the database settings.

                     To run on the throwaway database on purpose, start with
                     app.db.allow-throwaway=true
                    ------------------------------------------------------------------
                    """.formatted(url));
        }

        if (throwaway) {
            log.warn("Using the throwaway database {} - real data lives elsewhere", url);
        } else {
            log.info("Using {} at {}", product, url);
        }
    }
}
