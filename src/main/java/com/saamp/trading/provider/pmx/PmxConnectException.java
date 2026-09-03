package com.saamp.trading.provider.pmx;

/** Structured PMXConnect HTTP/API failure. */
public class PmxConnectException extends RuntimeException {
    private final int httpStatus;
    private final Integer errorCode;

    public PmxConnectException(int httpStatus, Integer errorCode, String message) {
        super(message == null || message.isBlank() ? "PMXConnect error HTTP " + httpStatus : message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public int httpStatus() { return httpStatus; }
    public Integer errorCode() { return errorCode; }

    public boolean isRequestNotFound631() {
        return httpStatus == 400 && Integer.valueOf(631).equals(errorCode);
    }

    public boolean blocksTransmissions() {
        return httpStatus == 401 || httpStatus == 403;
    }

    public boolean requiresImmediateAlert() {
        return blocksTransmissions() || (httpStatus == 400 && !isRequestNotFound631());
    }
}
