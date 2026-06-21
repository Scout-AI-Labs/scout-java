package com.scout;

/** Base (unchecked) exception for every error raised by the SDK. */
public class ScoutException extends RuntimeException {

    public ScoutException(String message) {
        super(message);
    }

    public ScoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
