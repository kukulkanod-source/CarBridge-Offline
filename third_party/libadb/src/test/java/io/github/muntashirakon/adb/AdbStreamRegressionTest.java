package io.github.muntashirakon.adb;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Real loopback ADB packets exercise the vendored Java transport, not an API stub. */
public class AdbStreamRegressionTest {
    private static java.security.KeyPair keys;
    private static Certificate certificate;

    @BeforeClass public static void identity() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        // This peer deliberately exercises plain ADB framing, not the TLS certificate path.
        certificate = new Certificate("X.509") {
            public byte[] getEncoded() { return keys.getPublic().getEncoded(); }
            public void verify(PublicKey key) {}
            public void verify(PublicKey key, String provider) {}
            public String toString() { return "Loopback test identity"; }
            public PublicKey getPublicKey() { return keys.getPublic(); }
        };
    }

    @Test(timeout = 8000) public void remoteCloseDrainsQueuedAndBufferedBytesThenEof() throws Exception {
        CountDownLatch sent = new CountDownLatch(1);
        try (Peer peer = new Peer(socket -> {
            AdbProtocol.Message open = read(socket);
            send(socket, AdbProtocol.generateReady(1, open.arg0));
            send(socket, AdbProtocol.generateWrite(1, open.arg0, new byte[]{(byte) 0x80, (byte) 0xff, 7}, 0, 3));
            assertEquals(AdbProtocol.A_OKAY, read(socket).command);
            send(socket, AdbProtocol.generateWrite(1, open.arg0, new byte[]{8, 9}, 0, 2));
            assertEquals(AdbProtocol.A_OKAY, read(socket).command);
            send(socket, AdbProtocol.generateClose(1, open.arg0));
            sent.countDown();
        }); AdbConnection connection = peer.connect(); AdbStream stream = connection.open("shell:test", 2000)) {
            assertTrue(sent.await(2, TimeUnit.SECONDS));
            AdbInputStream input = stream.openInputStream();
            assertEquals(128, input.read());
            assertEquals(255, input.read());
            byte[] tail = new byte[3];
            int position = 0;
            while (position < tail.length) position += input.read(tail, position, tail.length - position);
            assertArrayEquals(new byte[]{7, 8, 9}, tail);
            assertEquals(-1, input.read());
            assertEquals(0, input.read(new byte[0]));
            peer.await();
        }
    }

    @Test(timeout = 8000) public void eachFragmentWaitsForItsOwnOkay() throws Exception {
        byte[] payload = new byte[96];
        Arrays.fill(payload, (byte) 17);
        try (Peer peer = new Peer(socket -> {
            AdbProtocol.Message open = read(socket);
            send(socket, AdbProtocol.generateReady(1, open.arg0));
            for (int index = 0; index < 3; index++) {
                AdbProtocol.Message write = read(socket);
                assertEquals(AdbProtocol.A_WRTE, write.command);
                assertEquals(32, write.payload.length);
                if (index < 2) {
                    socket.setSoTimeout(100);
                    try {
                        read(socket);
                        fail("A second WRTE arrived before OKAY");
                    } catch (SocketTimeoutException expected) {
                        // Waiting for READY is required by ADB's per-stream flow control.
                    } finally { socket.setSoTimeout(3000); }
                }
                send(socket, AdbProtocol.generateReady(1, open.arg0));
            }
        }); AdbConnection connection = peer.connect(); AdbStream stream = connection.open("sync:", 2000)) {
            stream.write(payload, 0, payload.length);
            peer.await();
        }
    }

    @Test(timeout = 8000) public void rejectedAndUnansweredOpenAreBounded() throws Exception {
        try (Peer peer = new Peer(socket -> {
            AdbProtocol.Message open = read(socket);
            send(socket, AdbProtocol.generateClose(0, open.arg0));
            read(socket); // Next OPEN is deliberately unanswered.
            assertEquals(-1, socket.getInputStream().read()); // Timeout discards the transport.
        }); AdbConnection connection = peer.connect()) {
            try { connection.open("localabstract:missing", 1000); fail("Expected refusal"); }
            catch (java.net.ConnectException expected) {}
            try { connection.open("localabstract:no-answer", 100); fail("Expected timeout"); }
            catch (SocketTimeoutException expected) {}
            peer.await();
        }
    }

    @Test(timeout = 12000) public void parallelOpenUsesUniqueIdsAndImmediateOkayDoesNotGetLost() throws Exception {
        try (Peer peer = new Peer(socket -> {
            Set<Integer> ids = new HashSet<>();
            for (int index = 0; index < 32; index++) {
                AdbProtocol.Message message;
                do { message = read(socket); } while (message.command != AdbProtocol.A_OPEN);
                assertTrue(ids.add(message.arg0));
                send(socket, AdbProtocol.generateReady(index + 1, message.arg0));
            }
        }); AdbConnection connection = peer.connect()) {
            ExecutorService clients = Executors.newFixedThreadPool(8);
            try {
                java.util.List<Future<AdbStream>> opened = new java.util.ArrayList<>();
                for (int index = 0; index < 32; index++) {
                    opened.add(clients.submit(() -> connection.open("shell:echo", 2000)));
                }
                for (Future<AdbStream> future : opened) future.get(3, TimeUnit.SECONDS).close();
                peer.await();
            } finally { clients.shutdownNow(); }
        }
    }

    @Test(timeout = 8000) public void congestedSocketWriteTimesOutAndReplacementConnectionWorks() throws Exception {
        CountDownLatch releasePeer = new CountDownLatch(1);
        CountDownLatch peerReady = new CountDownLatch(1);
        try (Peer peer = new Peer(socket -> {
            // Real TCP backpressure: never read another byte after the CNXN handshake.
            socket.setReceiveBufferSize(1024);
            peerReady.countDown();
            assertTrue(releasePeer.await(5, TimeUnit.SECONDS));
        }); AdbConnection connection = peer.connect()) {
            assertTrue(peerReady.await(1, TimeUnit.SECONDS));
            byte[] packet = new byte[32 * 1024 * 1024];
            long started = System.nanoTime();
            try {
                connection.sendPacket(packet, 200);
                fail("An unread 32 MiB TCP write must be cancelled");
            } catch (SocketTimeoutException expected) { }
            assertTrue("Socket write exceeded its complete deadline",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500);
            assertFalse(connection.isConnected());
            releasePeer.countDown();
            peer.await();
        } finally { releasePeer.countDown(); }
        try (Peer replacement = new Peer(socket -> {
            AdbProtocol.Message open = read(socket);
            send(socket, AdbProtocol.generateReady(1, open.arg0));
            assertEquals(AdbProtocol.A_CLSE, read(socket).command);
        }); AdbConnection connection = replacement.connect(); AdbStream stream = connection.open("sync:", 1000)) {
            stream.close();
            replacement.await();
        }
    }

    @Test(timeout = 8000) public void openBudgetIncludesAContendedWriterAndCancelsTheBlockedWrite() throws Exception {
        CountDownLatch releasePeer = new CountDownLatch(1);
        ExecutorService client = Executors.newSingleThreadExecutor();
        try (Peer peer = new Peer(socket -> {
            socket.setReceiveBufferSize(1024);
            assertTrue(releasePeer.await(5, TimeUnit.SECONDS));
        }); AdbConnection connection = peer.connect()) {
            CountDownLatch startedWrite = new CountDownLatch(1);
            Future<?> writer = client.submit(() -> {
                startedWrite.countDown();
                try { connection.sendPacket(new byte[32 * 1024 * 1024], 4000); fail("Expected cancellation"); }
                catch (IOException expected) { }
            });
            assertTrue(startedWrite.await(1, TimeUnit.SECONDS));
            Thread.sleep(100); // The writer fills the actual OS send buffer.
            long started = System.nanoTime();
            try { connection.open("sync:", 150); fail("Expected timeout behind a blocked writer"); }
            catch (SocketTimeoutException expected) { }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500);
            writer.get(1, TimeUnit.SECONDS);
            assertFalse(connection.isConnected());
            releasePeer.countDown();
            peer.await();
        } finally { releasePeer.countDown(); client.shutdownNow(); }
    }

    @Test(timeout = 8000) public void cancellingTheTransportWakesAWriterWaitingForOkay() throws Exception {
        CountDownLatch firstWrite = new CountDownLatch(1);
        CountDownLatch releasePeer = new CountDownLatch(1);
        ExecutorService client = Executors.newSingleThreadExecutor();
        try (Peer peer = new Peer(socket -> {
            AdbProtocol.Message open = read(socket);
            send(socket, AdbProtocol.generateReady(1, open.arg0));
            assertEquals(AdbProtocol.A_WRTE, read(socket).command);
            firstWrite.countDown();
            // Withhold the next OKAY, so the second WRTE must wait until close().
            assertTrue(releasePeer.await(5, TimeUnit.SECONDS));
        }); AdbConnection connection = peer.connect(); AdbStream stream = connection.open("sync:", 1000)) {
            Future<?> writer = client.submit(() -> {
                try { stream.write(new byte[64], 0, 64); fail("Expected connection cancellation"); }
                catch (IOException expected) { }
            });
            assertTrue(firstWrite.await(1, TimeUnit.SECONDS));
            long started = System.nanoTime();
            connection.close();
            writer.get(1, TimeUnit.SECONDS);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500);
            releasePeer.countDown();
            peer.await();
        } finally { releasePeer.countDown(); client.shutdownNow(); }
    }

    @Test(timeout = 8000) public void closePreservesTheCallersInterruptStatus() throws Exception {
        try (Peer peer = new Peer(socket -> { assertEquals(-1, socket.getInputStream().read()); });
             AdbConnection connection = peer.connect()) {
            Thread.currentThread().interrupt();
            try { connection.close(); assertTrue(Thread.currentThread().isInterrupted()); }
            finally { Thread.interrupted(); }
            peer.await();
        }
    }

    private interface Handler { void run(Socket socket) throws Exception; }

    private static final class Peer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final Future<?> task;
        volatile Socket client;
        Peer(Handler handler) throws IOException {
            task = worker.submit(() -> {
                try {
                    client = server.accept();
                    client.setSoTimeout(3000);
                    assertEquals(AdbProtocol.A_CNXN, read(client).command);
                    send(client, AdbProtocol.generateMessage(AdbProtocol.A_CNXN,
                            AdbProtocol.A_VERSION_SKIP_CHECKSUM, 32, new byte[0]));
                    handler.run(client);
                } catch (Exception exception) { throw new RuntimeException(exception); }
            });
        }
        AdbConnection connect() throws Exception {
            AdbConnection connection = new AdbConnection.Builder("127.0.0.1", server.getLocalPort())
                    .setApi(34).setPrivateKey(keys.getPrivate()).setCertificate(certificate).build();
            assertTrue(connection.connect(2000, TimeUnit.MILLISECONDS, true));
            return connection;
        }
        void await() throws Exception { task.get(4, TimeUnit.SECONDS); }
        public void close() throws Exception {
            if (client != null) client.close();
            server.close();
            worker.shutdownNow();
        }
    }

    private static AdbProtocol.Message read(Socket socket) throws IOException {
        return AdbProtocol.Message.parse(socket.getInputStream(), AdbProtocol.A_VERSION_SKIP_CHECKSUM, 1 << 20);
    }
    private static void send(Socket socket, byte[] message) throws IOException {
        socket.getOutputStream().write(message);
        socket.getOutputStream().flush();
    }
}
