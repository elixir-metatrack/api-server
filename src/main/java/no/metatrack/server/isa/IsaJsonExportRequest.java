package no.metatrack.server.isa;

import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Schema(description = "Select 1–10,000 distinct sample names in the project for ISA-JSON export.")
public record IsaJsonExportRequest(List<String> sampleNames) {
    Set<String> validatedNames() {
        if (sampleNames == null || sampleNames.isEmpty() || sampleNames.size() > 10_000
                || sampleNames.stream().anyMatch(name -> name == null || name.isBlank())
                || new LinkedHashSet<>(sampleNames).size() != sampleNames.size()) {
            throw new BadRequestException("Select between 1 and 10,000 distinct samples to export");
        }
        return new LinkedHashSet<>(sampleNames);
    }
}
