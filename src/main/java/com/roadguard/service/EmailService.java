package com.roadguard.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    @Value("${spring.mail.username:}")
    private String fromAddress;

    public void sendPasswordResetCode(String toEmail, String code) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();

        if (mailSender != null && fromAddress != null && !fromAddress.isBlank()) {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setFrom(fromAddress, "RoadGuard");
                helper.setTo(toEmail);
                helper.setSubject("RoadGuard - Password Recovery Code: " + code);

                String html = """
                    <div style="font-family: Arial, sans-serif; max-width: 500px; margin: 0 auto; padding: 24px; border: 1px solid #e0e0e0; border-radius: 12px;">
                        <h2 style="color: #0b1a46; margin-top: 0;">RoadGuard Password Recovery</h2>
                        <p style="color: #555; font-size: 15px;">You requested a password reset for your RoadGuard account.</p>
                        <p style="color: #555; font-size: 15px;">Your 6-digit verification code is:</p>
                        <div style="background: #f0f4ff; border: 2px dashed #3060ff; padding: 14px; text-align: center; font-size: 26px; font-weight: bold; letter-spacing: 6px; color: #0b1a46; margin: 18px 0; border-radius: 8px;">
                            %s
                        </div>
                        <p style="color: #888; font-size: 13px;">This code expires in 15 minutes. If you did not request this code, you can safely ignore this email.</p>
                        <hr style="border: none; border-top: 1px solid #eee; margin: 20px 0;" />
                        <p style="color: #aaa; font-size: 12px; margin-bottom: 0;">RoadGuard Emergency Roadside Dispatch</p>
                    </div>
                """.formatted(code);

                helper.setText(html, true);
                mailSender.send(message);
                log.info("Successfully delivered password recovery email to {}", toEmail);
                return;
            } catch (Exception e) {
                log.error("Failed to send real email via SMTP to {}: {}", toEmail, e.getMessage());
            }
        } else {
            log.warn("Real SMTP mail sending is inactive because spring.mail.username / spring.mail.password are not set in application-local.properties.");
        }

        log.info("""

                ==========================================================
                 [RoadGuard] PASSWORD RECOVERY VERIFICATION CODE
                 To: {}
                 Code: {}
                 Lifespan: 15 minutes
                ==========================================================
                """, toEmail, code);
    }
}
