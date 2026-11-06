package no.metatrack.server.upload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SftpGoTransfer(
        @JsonProperty("operation_type") @JsonAlias({"type", "operation"}) String operationType,
        @JsonProperty("path") @JsonAlias({"virtual_path", "file_path"}) String path,
        @JsonProperty("size") @JsonAlias({"file_size", "current_size"})
        long size
) {
}