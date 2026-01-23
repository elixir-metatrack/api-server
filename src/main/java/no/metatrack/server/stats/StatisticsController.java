package no.metatrack.server.stats;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/api/statistics")
public class StatisticsController {

    @Inject
    StatisticsService statisticsService;

    @GET
    public MetatrackStatistics getStatistics() {
        return statisticsService.getStatistics();
    }
}
