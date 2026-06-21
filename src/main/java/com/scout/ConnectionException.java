package com.scout;

/** No HTTP response was received (DNS, refused connection, reset). */
public class ConnectionException extends ScoutException {

    public ConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
