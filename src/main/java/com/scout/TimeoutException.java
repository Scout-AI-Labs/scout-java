package com.scout;

/** The request exceeded the configured timeout before a response arrived. */
public class TimeoutException extends ConnectionException {

    public TimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
