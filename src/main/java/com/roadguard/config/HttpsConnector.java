package com.roadguard.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

@Configuration
@Slf4j
public class HttpsConnector {

    @Value("${app.https.port:8443}")
    private int httpsPort;

    @Value("${app.https.keystore:certs/roadguard.p12}")
    private String keystorePath;

    @Value("${app.https.password:roadguard}")
    private String keystorePassword;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> alsoServeHttps() {
        return factory -> {
            File keystore = new File(keystorePath);
            if (!keystore.isFile()) {
                log.info("No certificate at {} - serving plain http only. "
                        + "A phone will not be allowed to share its location over http.", keystorePath);
                return;
            }

            Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            connector.setPort(httpsPort);
            connector.setSecure(true);
            connector.setScheme("https");

            Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
            protocol.setSSLEnabled(true);

            SSLHostConfig sslConfig = new SSLHostConfig();
            SSLHostConfigCertificate certificate =
                    new SSLHostConfigCertificate(sslConfig, SSLHostConfigCertificate.Type.RSA);
            certificate.setCertificateKeystoreFile(keystore.getAbsolutePath());
            certificate.setCertificateKeystorePassword(keystorePassword);
            certificate.setCertificateKeystoreType("PKCS12");
            sslConfig.addCertificate(certificate);
            connector.addSslHostConfig(sslConfig);

            factory.addAdditionalTomcatConnectors(connector);
            log.info("Also serving https on {} so a phone can share its location", httpsPort);
        };
    }
}
