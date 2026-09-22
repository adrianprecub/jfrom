package io.jfrom.sample.workload;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/**
 * File and socket traffic: writes/reads a temp file, and makes a real
 * loopback socket round trip, producing {@code jdk.FileRead}/{@code FileWrite}
 * and {@code jdk.SocketRead}/{@code SocketWrite} events.
 */
@Component
public class IoWorkload {

    static final int MIN_SIZE_KB = 1;
    static final int MAX_SIZE_KB = 1024;

    public IoResult run(int sizeKb) throws IOException {
        int clampedKb = clamp(sizeKb, MIN_SIZE_KB, MAX_SIZE_KB);
        byte[] payload = new byte[clampedKb * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }

        FileRoundTrip fileRoundTrip = fileRoundTrip(payload);
        long socketBytesEchoed = loopbackRoundTrip(payload);

        return new IoResult(fileRoundTrip.bytesWritten(), fileRoundTrip.bytesRead(), socketBytesEchoed);
    }

    private FileRoundTrip fileRoundTrip(byte[] payload) throws IOException {
        Path tmp = Files.createTempFile("jfrom-io-", ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                out.write(payload);
            }
            byte[] readBack = Files.readAllBytes(tmp);
            return new FileRoundTrip(payload.length, readBack.length);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private long loopbackRoundTrip(byte[] payload) throws IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            int port = server.getLocalPort();
            Thread echoServer = new Thread(() -> {
                try (Socket accepted = server.accept()) {
                    byte[] received = accepted.getInputStream().readNBytes(payload.length);
                    accepted.getOutputStream().write(received);
                    accepted.getOutputStream().flush();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, "jfrom-io-echo");
            echoServer.setDaemon(true);
            echoServer.start();

            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
                client.getOutputStream().write(payload);
                client.getOutputStream().flush();
                byte[] echoed = client.getInputStream().readNBytes(payload.length);
                joinQuietly(echoServer);
                return echoed.length;
            }
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private record FileRoundTrip(long bytesWritten, long bytesRead) {
    }

    public record IoResult(long fileBytesWritten, long fileBytesRead, long socketBytesEchoed) {
    }
}
