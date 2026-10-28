package no.metatrack.server.spreadsheet;

import jakarta.ws.rs.BadRequestException;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.util.HashSet;
import java.util.List;

@Schema(description = "Select 1–10,000 distinct sample names in the project for spreadsheet export.")
public record SpreadsheetExportRequest(List<String> sampleNames) {
    List<String> validatedNames() {
        if (sampleNames == null || sampleNames.isEmpty() || sampleNames.size() > 10_000
                || sampleNames.stream().anyMatch(name -> name == null || name.isBlank())
                || new HashSet<>(sampleNames).size() != sampleNames.size()) {
            throw new BadRequestException("Select between 1 and 10,000 distinct samples to export");
        }
        return List.copyOf(sampleNames);
    }
}
