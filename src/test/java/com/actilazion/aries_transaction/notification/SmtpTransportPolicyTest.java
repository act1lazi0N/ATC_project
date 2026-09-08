package com.actilazion.aries_transaction.notification;

import com.actilazion.aries_transaction.notification.infrastructure.email.*;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class SmtpTransportPolicyTest {
    @Test
    void requiredStartTlsRejectsDowngradeBeforeEnvelopeOrContent() throws Exception {
        try (var smtp = new PlainSmtpServer()) {
            assertThatThrownBy(() -> gateway(smtp.port(), true).send(message()))
                    .isInstanceOf(EmailGatewayException.class);
            assertThat(smtp.commands()).anyMatch(line -> line.startsWith("EHLO"))
                    .noneMatch(line -> line.startsWith("MAIL") || line.startsWith("RCPT") || line.equals("DATA"));
        }
    }

    @Test
    void developmentTransportCanDeliverToPlaintextSmtp() throws Exception {
        try (var smtp = new PlainSmtpServer()) {
            gateway(smtp.port(), false).send(message());
            assertThat(smtp.commands()).contains("DATA").anyMatch(line -> line.contains("Test body"));
        }
    }

    @Test
    void authenticatedTransportLogsInBeforeSending() throws Exception {
        try (var smtp = new PlainSmtpServer(true)) {
            var sender = sender(smtp.port(), false);
            sender.setUsername("test-sender");
            sender.setPassword("test-only-password");
            sender.getJavaMailProperties().setProperty("mail.smtp.auth", "true");
            sender.getJavaMailProperties().setProperty("mail.smtp.auth.mechanisms", "PLAIN");
            new SmtpEmailGateway(sender).send(message());
            var commands = smtp.commands();
            assertThat(commands).contains("AUTH PLAIN [redacted]", "DATA");
            assertThat(commands.indexOf("AUTH PLAIN [redacted]")).isLessThan(commands.indexOf("DATA"));
        }
    }

    private SmtpEmailGateway gateway(int port, boolean required) {
        return new SmtpEmailGateway(sender(port, required));
    }

    private JavaMailSenderImpl sender(int port, boolean required) {
        var sender = new JavaMailSenderImpl();
        sender.setHost("localhost");
        sender.setPort(port);
        var properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(required));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(required));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.connectiontimeout", "2000");
        properties.setProperty("mail.smtp.timeout", "2000");
        properties.setProperty("mail.smtp.writetimeout", "2000");
        return sender;
    }

    private EmailMessage message() {
        return new EmailMessage("recipient@test.local", "sender@test.local", "Test subject",
                "Test body", "<p>Test body</p>", "transport-test");
    }

    private static class PlainSmtpServer implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final List<String> received = new CopyOnWriteArrayList<>();
        private final Future<?> exchange;

        PlainSmtpServer() throws IOException {
            this(false);
        }

        PlainSmtpServer(boolean authenticationRequired) throws IOException {
            listener.setSoTimeout(5000);
            exchange = executor.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    var writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII), true);
                    writer.print("220 localhost test SMTP\r\n"); writer.flush();
                    boolean data = false;
                    boolean authenticated = false;
                    for (String line; (line = reader.readLine()) != null;) {
                        received.add(line.startsWith("AUTH ") ? "AUTH PLAIN [redacted]" : line);
                        if (data && !line.equals(".")) continue;
                        if (data) {
                            data = false;
                            writer.print("250 accepted\r\n");
                        } else if (line.startsWith("EHLO") && authenticationRequired) {
                            writer.print("250-localhost\r\n250 AUTH PLAIN\r\n");
                        } else if (line.startsWith("AUTH PLAIN ")) {
                            String[] credentials = new String(java.util.Base64.getDecoder().decode(
                                    line.substring("AUTH PLAIN ".length())), StandardCharsets.US_ASCII).split("\0", -1);
                            authenticated = credentials.length == 3
                                    && (credentials[0].isEmpty() || credentials[0].equals("test-sender"))
                                    && credentials[1].equals("test-sender")
                                    && credentials[2].equals("test-only-password");
                            writer.print(authenticated ? "235 authenticated\r\n" : "535 rejected\r\n");
                        } else if (line.startsWith("MAIL") && authenticationRequired && !authenticated) {
                            writer.print("530 authentication required\r\n");
                        } else if (line.equals("DATA")) {
                            data = true;
                            writer.print("354 send data\r\n");
                        } else if (line.equals("QUIT")) {
                            writer.print("221 bye\r\n"); writer.flush(); break;
                        } else {
                            writer.print("250 localhost\r\n");
                        }
                        writer.flush();
                    }
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }

        int port() { return listener.getLocalPort(); }

        List<String> commands() throws Exception {
            exchange.get(10, TimeUnit.SECONDS);
            return received;
        }

        @Override
        public void close() throws Exception {
            listener.close();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
