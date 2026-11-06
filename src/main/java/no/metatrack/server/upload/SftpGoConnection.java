package no.metatrack.server.upload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SftpGoConnection(
        String username,
        @JsonProperty("connection_id") String connectionId,
        @JsonProperty("active_transfers") @JsonAlias("transfers") List<SftpGoTransfer> activeTransfers
) {
}