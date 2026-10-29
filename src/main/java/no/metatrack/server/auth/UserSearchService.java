package no.metatrack.server.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;

@ApplicationScoped
public class UserSearchService {
    private static final int MIN_QUERY_LENGTH = 2;
    private static final int MAX_QUERY_LENGTH = 256;

    private final UserSearchCache userSearchCache;

    public UserSearchService(UserSearchCache userSearchCache) {
        this.userSearchCache = userSearchCache;
    }

    public UserSearchResponse search(String query) {
        String normalizedQuery = normalizeQuery(query);
        return userSearchCache.searchNormalizedQuery(normalizedQuery);
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            throw new BadRequestException("A search query is required");
        }
        String normalizedQuery = query.strip();
        int queryLength = normalizedQuery.codePointCount(0, normalizedQuery.length());
        if (queryLength < MIN_QUERY_LENGTH || queryLength > MAX_QUERY_LENGTH
                || normalizedQuery.indexOf('*') >= 0 || normalizedQuery.indexOf('"') >= 0) {
            throw new BadRequestException("The search query is invalid");
        }
        return normalizedQuery;
    }

}