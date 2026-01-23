package no.metatrack.server.stats;

import jakarta.enterprise.context.ApplicationScoped;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.project.Project;
import no.metatrack.server.sample.Sample;

import java.time.Instant;

@ApplicationScoped
public class StatisticsService {
    public MetatrackStatistics getStatistics() {
        long projectCount = Project.count();
        long sampleCount = Sample.count();
        long assayCount = Assay.count();

        Instant lastUpdated = Sample.<Sample>find("order by createdOn desc")
                .firstResultOptional()
                .map(s -> s.createdOn)
                .orElse(null);

        return new MetatrackStatistics(projectCount, sampleCount, assayCount, lastUpdated);
    }
}
