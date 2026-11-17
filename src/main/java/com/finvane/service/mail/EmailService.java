package com.finvane.service.mail;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.Properties;

@Slf4j
@Service
public class EmailService {

    @Value("${spring.mail.host:smtp.gmail.com}")
    private String host;

    @Value("${spring.mail.port:587}")
    private int port;

    @Value("${spring.mail.username:}")
    private String username;

    @Value("${spring.mail.password:}")
    private String password;

    @Value("${app.mail.from:noreply@finvane.com}")
    private String mailFrom;

    public boolean isConfigured() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    public boolean sendOtpEmail(String toEmail, String otp) {
        if (!isConfigured()) {
            log.warn("SMTP username/password not configured in application.yml or .env. Real mail dispatch skipped. Generated OTP for {}: {}", toEmail, otp);
            return false;
        }
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(host);
            sender.setPort(port);
            sender.setUsername(username);
            sender.setPassword(password);

            Properties props = sender.getJavaMailProperties();
            props.put("mail.transport.protocol", "smtp");
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "10000");
            props.put("mail.smtp.writetimeout", "10000");

            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            String senderAddr = (mailFrom != null && !mailFrom.isBlank()) ? mailFrom : username;
            helper.setFrom(senderAddr, "FinVane ERP");
            helper.setTo(toEmail);
            helper.setSubject("FinVane ERP Registration OTP: " + otp);

            String html = """
                <div style='font-family: Arial, sans-serif; max-width: 480px; margin: 0 auto; padding: 24px; border: 1px solid #e2e8f0; border-radius: 12px;'>
                    <h2 style='color: #0d9488; margin-top: 0;'>FinVane ERP</h2>
                    <p style='color: #334155; font-size: 15px;'>Hello,</p>
                    <p style='color: #334155; font-size: 15px;'>Your verification code for registering your business entity is:</p>
                    <div style='background-color: #f8fafc; border: 1px solid #cbd5e1; padding: 18px; border-radius: 8px; text-align: center; font-size: 32px; font-weight: 700; letter-spacing: 6px; color: #0f172a; margin: 24px 0;'>
                        """ + otp + """
                    </div>
                    <p style='color: #64748b; font-size: 13px;'>This OTP is valid for 10 minutes. If you did not request this code, you can safely ignore this email.</p>
                </div>
            """;

            helper.setText(html, true);
            sender.send(message);
            log.info("Successfully dispatched OTP email to {}", toEmail);
            return true;
        } catch (Exception e) {
            log.error("Failed to send OTP email to {}: {}", toEmail, e.getMessage());
            return false;
        }
    }

    public java.util.Map<String, Object> testSmtpConnection(String testRecipient) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("host", host);
        result.put("port", port);
        result.put("configured", isConfigured());
        result.put("username", username != null && !username.isBlank() ? (username.substring(0, Math.min(3, username.length())) + "***") : "not configured");
        if (!isConfigured()) {
            result.put("ok", false);
            result.put("message", "SMTP credentials (SMTP_USER, SMTP_PASS) not configured in environment or application.yml. OTP fallback works seamlessly.");
            return result;
        }
        try {
            boolean sent = sendOtpEmail(testRecipient, "123456");
            result.put("ok", sent);
            result.put("message", sent ? "Test email dispatched successfully to " + testRecipient : "Failed to dispatch test email. Check server log.");
        } catch (Exception e) {
            result.put("ok", false);
            result.put("error", e.getMessage());
        }
        return result;
    }
}

