package no.metatrack.server.stats;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

@Path("/api/statistics")
public class StatisticsController {
    static final int MAX_PAGE_SIZE = 100;

    @Inject
    StatisticsService statisticsService;

    @GET
    public MetatrackStatistics getStatistics() {
        return statisticsService.getStatistics();
    }

    @GET
    @Path("/samples-by-date")
    public DailySampleCountPage getSamplesByDate(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        if (page < 0) throw new BadRequestException("page must be zero or greater");
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }

        return statisticsService.getDailySampleCounts(page, size);
    }
}
