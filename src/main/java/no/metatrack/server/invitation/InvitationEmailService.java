package no.metatrack.server.invitation;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;

@Startup
@ApplicationScoped
public class InvitationEmailService {
    private final Mailer mailer;
    private final String loginUrl;
    private final String registrationUrl;

    @Inject
    public InvitationEmailService(Mailer mailer,
            @ConfigProperty(name = "metatrack.invitation.login-url") String loginUrl,
            @ConfigProperty(name = "metatrack.invitation.registration-url") String registrationUrl) {
        this(mailer, loginUrl, registrationUrl, LaunchMode.current() == LaunchMode.NORMAL);
    }

    InvitationEmailService(Mailer mailer, String loginUrl, String registrationUrl, boolean production) {
        this.mailer = mailer;
        this.loginUrl = validateUrl(loginUrl, production);
        this.registrationUrl = validateUrl(registrationUrl, production);
    }

    public Mail render(InvitationDeliveryTransactions.Envelope invitation, boolean registered) {
        String url = registered ? loginUrl : registrationUrl;
        String action = registered ? "Sign in to view your invitation" : "Register to view your invitation";
        String description = invitation.inviter() + " invited you to project " + invitation.project()
                + " with role " + invitation.role() + ".";
        String expiry = "This invitation expires at " + invitation.expiresOn() + ".";
        String notice = "Sign in with the invited email address and verify it. Accept the invitation in your inbox to join; opening this link does not grant access.";
        return Mail.withText(invitation.email(), "Project invitation", description + "\n" + expiry + "\n"
                        + action + ": " + url + "\n" + notice)
                .setHtml("<p>" + escape(description) + "</p><p>" + escape(expiry) + "</p><p><a href=\""
                        + escape(url) + "\">" + action + "</a></p><p>" + escape(notice) + "</p>");
    }

    @Transactional(Transactional.TxType.NEVER)
    public void send(Mail mail) {
        mailer.send(mail);
    }

    private static String validateUrl(String value, boolean production) {
        URI uri = URI.create(value);
        boolean localHttp = !production && "http".equals(uri.getScheme())
                && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
        if ((!"https".equals(uri.getScheme()) && !localHttp) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || value.contains("\\") || !value.equals(uri.toASCIIString()) || uri.getPort() == 0) {
            throw new IllegalArgumentException("Invitation entry URLs must be trusted absolute HTTPS SPA routes without credentials, query or fragment");
        }
        return uri.toASCIIString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}