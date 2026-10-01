package no.metatrack.server.sample.vocabulary;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import no.metatrack.server.sample.metadata.SampleMetadataField;
import no.metatrack.server.sample.metadata.SampleMetadataFieldType;
import no.metatrack.server.spreadsheet.SpreadsheetTable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class SpreadsheetVocabularyImportService {
    @Inject
    SampleVocabularyManagementService vocabularyService;

    @Inject
    ProjectRoleCheck roleCheck;

    @Transactional
    public void apply(Long projectId, SpreadsheetTable table, List<SampleMetadataField> fields) {
        if (!table.vocabularies().isEmpty()) {
            // Serialize definition checks and creation so two imports cannot both observe a missing vocabulary.
            Project.findById(projectId, LockModeType.PESSIMISTIC_WRITE);
        }
        Map<String, List<String>> additions = new LinkedHashMap<>();
        for (var entry : table.vocabularies().entrySet()) {
            String key = entry.getKey();
            if (SampleVocabularyBuiltInCatalog.find(key).isPresent()) {
                throw new BadRequestException("Vocabularies: built-in attribute '" + key
                        + "' uses an administrator-managed vocabulary");
            }
            fields.stream()
                    .filter(field -> field.key.equals(key)
                            && field.archivedOn == null
                            && field.type == SampleMetadataFieldType.TEXT)
                    .findFirst().orElseThrow(() -> new BadRequestException(
                            "Vocabularies: '" + key + "' must be an existing custom TEXT attribute"));
            if (table.headers().stream().noneMatch(header -> key.equalsIgnoreCase(header))) {
                throw new BadRequestException("Vocabularies: custom attribute '" + key
                        + "' must appear by its field key in the data sheet");
            }
            List<String> values = SampleVocabularyManagementService.validateTerms(entry.getValue());
            Optional<SampleVocabulary> existing = SampleVocabulary
                    .<SampleVocabulary>find("project.id = ?1 and fieldKey = ?2", projectId, key)
                    .firstResultOptional();
            if (existing.isPresent()) {
                Set<String> current = new HashSet<>();
                existing.get().terms.forEach(term -> current.add(term.value));
                if (!current.equals(new HashSet<>(values))) {
                    throw new BadRequestException("Vocabularies: definition for '" + key
                            + "' conflicts with the project vocabulary; update it separately");
                }
            } else {
                additions.put(key, values);
            }
        }
        // Validate the whole sheet and permissions before writing any definition or sample.
        if (!additions.isEmpty() && !roleCheck.isAtLeast(projectId, ProjectRole.ADMIN)) {
            throw new ForbiddenException("Only project administrators can import new vocabularies");
        }
        additions.forEach((key, values) -> vocabularyService.createFromImport(projectId, key, values));
    }
}
