package no.metatrack.server.stats;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/statistics")
@Tag(name = "Statistics", description = "Public platform-wide sample and storage statistics")
public class StatisticsController {
    static final int MAX_PAGE_SIZE = 100;

    @Inject
    StatisticsService statisticsService;

    @GET
    @Operation(summary = "Get platform statistics", description = "Public summary statistics for the platform.")
    public MetatrackStatistics getStatistics() {
        return statisticsService.getStatistics();
    }

    @GET
    @Path("/storage")
    @Operation(summary = "Get platform storage statistics", description = "Public storage summary for the platform.")
    public StorageStatistics getStorageStatistics() {
        return statisticsService.getStorageStatistics();
    }

    @GET
    @Path("/samples-by-date")
    @Operation(summary = "Get daily sample counts", description = "Public zero-based pagination of daily sample counts; page size is 1–100.")
    @APIResponse(responseCode = "400", description = "Page must be non-negative and size must be 1–100")
    public DailySampleCountPage getSamplesByDate(
            @Parameter(description = "Zero-based page index", example = "0")
            @QueryParam("page") @DefaultValue("0") int page,
            @Parameter(description = "Number of results per page; maximum 100", example = "20")
            @QueryParam("size") @DefaultValue("20") int size) {
        if (page < 0) throw new BadRequestException("page must be zero or greater");
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
        }

        return statisticsService.getDailySampleCounts(page, size);
    }
}
