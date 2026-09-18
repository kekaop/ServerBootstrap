package dev.kekaop.ServerBootstrap;

import java.io.IOException;

/** Only controlled, credential-free messages may cross the core/UI boundary. */
public final class Failure extends IOException {
    public Failure(String message) { super(message); }
}
