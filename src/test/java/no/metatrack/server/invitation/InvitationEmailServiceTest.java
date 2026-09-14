package no.metatrack.server.invitation;

import io.quarkus.mailer.Mailer;
import no.metatrack.server.project.ProjectRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvitationEmailServiceTest {
    @Test
    void rendersBothVariantsWithEscapedHtmlAndPlainText() {
        var mailer = mock(Mailer.class);
        var service = new InvitationEmailService(mailer, "https://spa.example/login", "https://spa.example/register", true);
        var envelope = new InvitationDeliveryTransactions.Envelope("recipient@example.org", false,
                "<script>\"Owner\" & 'friend'</script>", "<b>Project</b>", ProjectRole.EDITOR, Instant.parse("2026-09-17T10:00:00Z"));
        for (boolean registered : new boolean[]{true, false}) {
            var mail = service.render(envelope, registered);
            assertTrue(mail.getText().contains(envelope.inviter()));
            assertTrue(mail.getText().contains(envelope.project()));
            assertTrue(mail.getText().contains("EDITOR"));
            assertTrue(mail.getText().contains(envelope.expiresOn().toString()));
            assertFalse(mail.getHtml().contains("<script>"));
            assertFalse(mail.getHtml().contains("<b>"));
            assertTrue(mail.getHtml().contains("&lt;script&gt;&quot;Owner&quot; &amp; &#39;friend&#39;"));
            String route = registered ? "/login" : "/register";
            assertTrue(mail.getHtml().contains("href=\"https://spa.example" + route + "\""));
            assertTrue(mail.getText().contains("https://spa.example" + route));
            service.send(mail);
            verify(mailer).send(mail);
        }
    }

    @Test
    void rejectsUnsafeEntryUrlsAndAllowsOnlyLocalHttpOutsideProduction() {
        for (String url : new String[]{"/login", "//evil.example/login", "javascript:alert(1)",
                "https://user:secret@spa.example/login", "https://spa.example/login?next=https://evil.example",
                "https://spa.example/#login", "http://spa.example/login", "https://spa.example/\r\nheader", "https://spa.example:0/login"}) {
            assertThrows(IllegalArgumentException.class, () -> new InvitationEmailService(mock(Mailer.class), url, url, true));
        }
        assertThrows(IllegalArgumentException.class, () -> new InvitationEmailService(mock(Mailer.class),
                "http://localhost/login", "http://localhost/register", true));
        assertDoesNotThrow(() -> new InvitationEmailService(mock(Mailer.class),
                "http://localhost:3000/login", "http://127.0.0.1:3000/register", false));
    }
}