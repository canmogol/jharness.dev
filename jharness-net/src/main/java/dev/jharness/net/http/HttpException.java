package dev.jharness.net.http;

/** An {@code api.http} or {@code api.fetch} call that failed after its request was checked (spec 016). */
public final class HttpException extends RuntimeException {

    public HttpException(String message) {
        super(message);
    }
}
