package com.actilazion.aries_transaction.notification;

import jakarta.mail.Session;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class SmtpTlsIdentityTest {
    @TempDir static Path temporaryDirectory;
    private static SSLContext serverContext;
    private static SSLContext trustedClientContext;

    @BeforeAll
    static void generateDisposableLocalhostCertificate() throws Exception {
        Path store = temporaryDirectory.resolve("smtp.p12");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "smtp", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", "test-password", "-keypass", "test-password", "-noprompt")
                .redirectErrorStream(true).redirectOutput(temporaryDirectory.resolve("keytool.log").toFile()).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Test certificate generation timed out");
        }
        assertThat(process.exitValue()).isZero();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keyStore.load(input, "test-password".toCharArray()); }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keyStore, "test-password".toCharArray());
        serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keys.getKeyManagers(), null, null);
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keyStore);
        trustedClientContext = SSLContext.getInstance("TLS");
        trustedClientContext.init(null, trust.getTrustManagers(), null);
    }

    @Test
    void untrustedCertificateIsRejectedBeforeEnvelope() throws Exception {
        try (var smtp = new TlsSmtpServer()) {
            assertThatThrownBy(() -> connect(smtp.port(), "localhost", false)).isInstanceOf(jakarta.mail.MessagingException.class);
            assertThat(smtp.commands()).contains("STARTTLS").noneMatch(line -> line.startsWith("MAIL"));
        }
    }

    @Test
    void trustedCertificateWithWrongHostnameIsRejected() throws Exception {
        try (var smtp = new TlsSmtpServer()) {
            assertThatThrownBy(() -> connect(smtp.port(), "127.0.0.1", true))
                    .isInstanceOf(jakarta.mail.MessagingException.class);
            assertThat(smtp.commands()).contains("STARTTLS").noneMatch(line -> line.startsWith("MAIL"));
        }
    }

    @Test
    void trustedCertificateWithMatchingHostnameCanConnect() throws Exception {
        try (var smtp = new TlsSmtpServer()) {
            connect(smtp.port(), "localhost", true);
            assertThat(smtp.commands()).contains("STARTTLS", "QUIT");
        }
    }

    private void connect(int port, String host, boolean trustCertificate) throws Exception {
        var properties = new Properties();
        properties.setProperty("mail.smtp.starttls.enable", "true");
        properties.setProperty("mail.smtp.starttls.required", "true");
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.connectiontimeout", "2000");
        properties.setProperty("mail.smtp.timeout", "2000");
        if (trustCertificate) properties.put("mail.smtp.ssl.socketFactory", trustedClientContext.getSocketFactory());
        try (var transport = Session.getInstance(properties).getTransport("smtp")) {
            transport.connect(host, port, null, null);
        }
    }

    private static class TlsSmtpServer implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final List<String> received = new CopyOnWriteArrayList<>();
        private final Future<?> exchange;

        TlsSmtpServer() throws IOException {
            listener.setSoTimeout(5000);
            exchange = executor.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    var reader = reader(socket);
                    var writer = writer(socket);
                    reply(writer, "220 localhost test SMTP");
                    SSLSocket tls = null;
                    try {
                        for (String line; (line = reader.readLine()) != null;) {
                            received.add(line);
                            if (line.startsWith("EHLO")) {
                                reply(writer, "250-localhost\r\n250 STARTTLS");
                            } else if (line.equals("STARTTLS")) {
                                reply(writer, "220 ready");
                                tls = (SSLSocket) serverContext.getSocketFactory().createSocket(socket, "localhost", socket.getPort(), false);
                                tls.setUseClientMode(false);
                                tls.setSoTimeout(5000);
                                tls.startHandshake();
                                reader = reader(tls);
                                writer = writer(tls);
                            } else if (line.equals("QUIT")) {
                                reply(writer, "221 bye");
                                break;
                            } else reply(writer, "250 accepted");
                        }
                    } catch (SSLException | SocketException expectedClientRejection) {
                        // The negative tests intentionally reject the handshake or close after hostname verification.
                    } finally {
                        if (tls != null) tls.close();
                    }
                } catch (IOException ex) { throw new UncheckedIOException(ex); }
            });
        }

        int port() { return listener.getLocalPort(); }
        List<String> commands() throws Exception { exchange.get(10, TimeUnit.SECONDS); return received; }
        private static BufferedReader reader(Socket socket) throws IOException {
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        }
        private static PrintWriter writer(Socket socket) throws IOException {
            return new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
        }
        private static void reply(PrintWriter writer, String reply) { writer.print(reply + "\r\n"); writer.flush(); }
        @Override public void close() throws Exception {
            listener.close(); executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
