package no.metatrack.server.notification;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.BadRequestException;
import no.metatrack.server.auth.UserService;

import java.util.UUID;

@ApplicationScoped
public class NotificationService {
    private final UserService users;
    private final NotificationTransactions transactions;

    public NotificationService(UserService users, NotificationTransactions transactions) {
        this.users = users;
        this.transactions = transactions;
    }

    public NotificationSyncResponse synchronize() {
        return transactions.synchronize(users.requireVerifiedEmailIdentity());
    }

    public NotificationPage list(int page, int size, Boolean unread) {
        UUID recipient = UUID.fromString(users.requireCurrentUser().id());
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new BadRequestException("Invalid pagination");
        }
        return transactions.list(recipient, page, size, unread);
    }

    public void updateRead(UUID id, UpdateNotificationRequest request) {
        UUID recipient = UUID.fromString(users.requireCurrentUser().id());
        if (request == null || request.read() == null) {
            throw new BadRequestException("Read state is required");
        }
        transactions.updateRead(recipient, id, request.read());
    }
}