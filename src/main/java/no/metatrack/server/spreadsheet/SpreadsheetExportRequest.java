package no.metatrack.server.spreadsheet;

import jakarta.ws.rs.BadRequestException;

import java.util.HashSet;
import java.util.List;

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
