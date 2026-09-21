package no.metatrack.server.assay.vocabulary;

import java.util.List;

public class AssayVocabularyValidationException extends RuntimeException {
    private final List<AssayValidationViolation> violations;

    public AssayVocabularyValidationException(List<AssayValidationViolation> violations) {
        super("Assay values violate configured vocabularies");
        this.violations = List.copyOf(violations);
    }

    public List<AssayValidationViolation> violations() {
        return violations;
    }
}