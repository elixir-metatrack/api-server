package no.metatrack.server.auth;

import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import no.metatrack.server.auth.keycloak.KeycloakAdminClient;
import no.metatrack.server.auth.keycloak.KeycloakIdentityException;
import no.metatrack.server.auth.keycloak.KeycloakProfileRepresentation;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.RestResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestProfile(UserSearchCacheTestProfile.class)
class UserSearchCacheTest {
    @Inject
    UserSearchService userSearchService;

    private KeycloakAdminClient keycloakAdminClient;

    @BeforeEach
    void installKeycloakMock() {
        keycloakAdminClient = mock(KeycloakAdminClient.class);
        QuarkusMock.installMockForType(keycloakAdminClient, KeycloakAdminClient.class, RestClient.LITERAL);
    }

    @Test
    void cachesSuccessfulAndEmptyResultsAndKeepsDistinctQueriesSeparate() {
        AtomicInteger calls = stubSuccessfulSearches();
        String query = query();

        UserSearchResponse first = userSearchService.search(query);
        UserSearchResponse cached = userSearchService.search(query);
        assertSame(first, cached);
        assertEquals(1, calls.get());

        String emptyQuery = query();
        doReturn(RestResponse.ok(List.of())).when(keycloakAdminClient)
                .searchUsers("test", emptyQuery, false, 0, 21);
        assertEquals(List.of(), userSearchService.search(emptyQuery).users());
        assertEquals(List.of(), userSearchService.search(emptyQuery).users());
        verify(keycloakAdminClient, times(1)).searchUsers("test", emptyQuery, false, 0, 21);

        String distinctQuery = query();
        userSearchService.search(distinctQuery);
        assertEquals(2, calls.get());
    }

    @Test
    void expiresResultsAfterConfiguredTtl() throws InterruptedException {
        AtomicInteger calls = stubSuccessfulSearches();
        String query = query();

        userSearchService.search(query);
        userSearchService.search(query);
        assertEquals(1, calls.get());

        Thread.sleep(450);
        userSearchService.search(query);
        assertEquals(2, calls.get());
    }

    @Test
    void enforcesConfiguredMaximumCacheSize() {
        AtomicInteger calls = stubSuccessfulSearches();
        List<String> queries = List.of(query(), query(), query());

        queries.forEach(userSearchService::search);
        assertEquals(3, calls.get());
        queries.forEach(userSearchService::search);

        assertTrue(calls.get() > 3);
    }

    @Test
    void concurrentIdenticalSearchesShareOneLoad() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(keycloakAdminClient.searchUsers(anyString(), anyString(), anyBoolean(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    calls.incrementAndGet();
                    Thread.sleep(100);
                    return RestResponse.ok(List.of(profile()));
                });
        String query = query();
        int requestCount = 6;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<CompletableFuture<UserSearchResponse>> requests = java.util.stream.IntStream.range(0, requestCount)
                    .mapToObj(ignored -> CompletableFuture.supplyAsync(() -> {
                        ready.countDown();
                        try {
                            start.await();
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(exception);
                        }
                        return userSearchService.search(query);
                    }, executor))
                    .toList();
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);

            assertEquals(1, calls.get());
            for (CompletableFuture<UserSearchResponse> request : requests) {
                assertEquals(1, request.join().users().size());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void upstreamFailuresAreNotCachedAndSuccessfulRetryIsCached() {
        AtomicInteger calls = new AtomicInteger();
        String query = query();
        when(keycloakAdminClient.searchUsers("test", query, false, 0, 21)).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return RestResponse.status(503);
            }
            return RestResponse.ok(List.of(profile()));
        });

        assertThrows(KeycloakIdentityException.class, () -> userSearchService.search(query));
        UserSearchResponse result = userSearchService.search(query);
        assertSame(result, userSearchService.search(query));
        assertEquals(2, calls.get());
    }

    private AtomicInteger stubSuccessfulSearches() {
        AtomicInteger calls = new AtomicInteger();
        when(keycloakAdminClient.searchUsers(anyString(), anyString(), anyBoolean(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    calls.incrementAndGet();
                    return RestResponse.ok(List.of(profile()));
                });
        return calls;
    }

    private String query() {
        return "user-" + UUID.randomUUID();
    }

    private KeycloakProfileRepresentation profile() {
        String id = UUID.randomUUID().toString();
        return new KeycloakProfileRepresentation(id, "user@example.org", "user@example.org", "Test", "User", null);
    }
}