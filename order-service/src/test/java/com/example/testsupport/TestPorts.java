package com.example.testsupport;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hands out TCP ports that are free AND not already handed out in this JVM.
 *
 * <p>Why this exists: several test classes use {@code webEnvironment = DEFINED_PORT} because they
 * need the port BEFORE the context is built (a bean captures a base URL in its constructor, so
 * {@code @LocalServerPort} is injected too late). The naive "bind to port 0, read the port, close
 * the socket" trick is fine in isolation, but JUnit loads every test class during discovery, so
 * two static initialisers can run before either server binds - and the OS happily returns the same
 * free port twice. The symptom is a test suite that passes class-by-class and fails as a whole with
 * {@code PortInUseException}. Remembering what has been issued removes the race.
 *
 * <p>The general lesson: a test that passes alone and fails in the suite is almost always sharing
 * mutable global state - a port, a static field, a file, a database table, a system property, or a
 * cached Spring context.
 */
public final class TestPorts {

    private static final Set<Integer> ISSUED = ConcurrentHashMap.newKeySet();

    private TestPorts() {
    }

    public static int reserve() {
        for (int attempt = 0; attempt < 50; attempt++) {
            try (ServerSocket socket = new ServerSocket(0)) {
                int port = socket.getLocalPort();
                if (ISSUED.add(port)) {
                    return port;
                }
            } catch (IOException ex) {
                throw new IllegalStateException("could not reserve a free port", ex);
            }
        }
        throw new IllegalStateException("could not reserve a distinct free port after 50 attempts");
    }
}
