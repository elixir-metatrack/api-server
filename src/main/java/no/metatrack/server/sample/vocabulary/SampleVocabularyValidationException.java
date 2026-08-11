package no.metatrack.server.sample.vocabulary;

import java.util.List;

public class SampleVocabularyValidationException extends RuntimeException {
    private final List<SampleValidationViolation> violations;

    public SampleVocabularyValidationException(List<SampleValidationViolation> violations) {
        super("Sample values violate configured vocabularies");
        this.violations = List.copyOf(violations);
    }

    public List<SampleValidationViolation> violations() {
        return violations;
    }
}