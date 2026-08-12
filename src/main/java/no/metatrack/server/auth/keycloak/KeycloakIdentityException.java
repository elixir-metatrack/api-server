package no.metatrack.server.auth.keycloak;

public class KeycloakIdentityException extends RuntimeException {
    public KeycloakIdentityException(String message) {
        super(message);
    }

    public KeycloakIdentityException(String message, Throwable cause) {
        super(message, cause);
    }
}