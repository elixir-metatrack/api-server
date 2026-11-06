package no.metatrack.server.upload;

public class SftpGoIntegrationException extends RuntimeException {
    public SftpGoIntegrationException(String message) {
        super(message);
    }

    public SftpGoIntegrationException(String message, Throwable cause) {
        super(message, cause);
    }
}