package no.metatrack.server.notification;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import no.metatrack.server.auth.CurrentUser;
import no.metatrack.server.auth.UserService;
import no.metatrack.server.auth.VerifiedEmailIdentity;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationServiceTest {
    @Test
    void synchronizationRequiresVerifiedIdentityBeforeStorage() {
        var users = mock(UserService.class);
        var transactions = mock(NotificationTransactions.class);
        var service = new NotificationService(users, transactions);
        when(users.requireVerifiedEmailIdentity()).thenThrow(new ForbiddenException());
        assertThrows(ForbiddenException.class, service::synchronize);
        verifyNoInteractions(transactions);
        var identity = new VerifiedEmailIdentity(UUID.randomUUID(), " User@Example.org ");
        doReturn(identity).when(users).requireVerifiedEmailIdentity();
        when(transactions.synchronize(identity)).thenReturn(new NotificationSyncResponse(1));
        assertEquals(1, service.synchronize().created());
        verify(transactions).synchronize(identity);
    }

    @Test
    void inboxAndUpdatesUseAuthenticatedSubjectAndValidateInputs() {
        UUID subject = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        var users = mock(UserService.class);
        var transactions = mock(NotificationTransactions.class);
        var service = new NotificationService(users, transactions);
        when(users.requireCurrentUser()).thenReturn(new CurrentUser(subject.toString(), "User", Set.of(), null, null, null));
        for (int[] pagination : new int[][]{{-1, 20}, {0, 0}, {0, 101}, {Integer.MAX_VALUE, 100}}) {
            assertThrows(BadRequestException.class, () -> service.list(pagination[0], pagination[1], null));
        }
        assertThrows(BadRequestException.class, () -> service.updateRead(id, null));
        assertThrows(BadRequestException.class, () -> service.updateRead(id, new UpdateNotificationRequest(null)));
        verifyNoInteractions(transactions);
        service.list(0, 100, true);
        service.updateRead(id, new UpdateNotificationRequest(false));
        verify(transactions).list(subject, 0, 100, true);
        verify(transactions).updateRead(subject, id, false);
        verify(users, never()).requireVerifiedEmailIdentity();
    }
}