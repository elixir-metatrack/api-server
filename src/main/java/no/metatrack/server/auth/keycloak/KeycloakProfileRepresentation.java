package no.metatrack.server.auth.keycloak;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakProfileRepresentation(String id, String username, String firstName, String lastName,
                                             Map<String, List<String>> attributes) {}