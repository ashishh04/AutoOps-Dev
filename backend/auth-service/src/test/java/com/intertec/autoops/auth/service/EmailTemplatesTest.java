package com.intertec.autoops.auth.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailTemplatesTest {

    @Test
    void theCodeAppearsInBothParts() {
        EmailTemplates.Rendered email = EmailTemplates.otpCode("481920", 5);

        assertTrue(email.html().contains("481920"));
        assertTrue(email.text().contains("481920"),
                "a client that shows only the text part must still be usable");
        assertEquals("Your AutoOps verification code", email.subject());
    }

    @Test
    void theExpiryIsStatedInBothParts() {
        EmailTemplates.Rendered email = EmailTemplates.otpCode("481920", 5);

        assertTrue(email.html().contains("5 minutes"));
        assertTrue(email.text().contains("5 minutes"));
    }

    @Test
    void aOneMinuteExpiryIsNotPluralised() {
        assertTrue(EmailTemplates.otpCode("1", 1).text().contains("1 minute."));
    }

    @Test
    void noPlaceholderSurvivesRendering() {
        // A template edited without its value being supplied would otherwise
        // mail "{{code}}" to a customer.
        EmailTemplates.Rendered otp = EmailTemplates.otpCode("481920", 5);
        EmailTemplates.Rendered invite =
                EmailTemplates.workspaceInvite("Acme", "http://localhost:5173");

        for (String body : new String[]{otp.html(), otp.text(), invite.html(), invite.text()}) {
            assertFalse(body.contains("{{"), "unsubstituted placeholder left in: " + body);
        }
    }

    @Test
    void theHtmlIsAWholeDocumentWithPreviewText() {
        String html = EmailTemplates.otpCode("481920", 5).html();

        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("</html>"));
        // The inbox preview line. Without it clients scrape the first visible
        // words, which would be the wordmark on every single email.
        assertTrue(html.contains("481920 is your AutoOps verification code"));
    }

    @Test
    void theInviteLinkIsBuiltFromTheConfiguredConsoleUrl() {
        String html = EmailTemplates.workspaceInvite("Acme", "https://app.example.com").html();

        assertTrue(html.contains("https://app.example.com/login"));
    }

    @Test
    void aTrailingSlashOnTheConsoleUrlDoesNotDoubleUp() {
        String html = EmailTemplates.workspaceInvite("Acme", "https://app.example.com/").html();

        assertTrue(html.contains("https://app.example.com/login"));
        assertFalse(html.contains("example.com//login"));
    }

    @Test
    void aWorkspaceNameCannotInjectMarkupIntoSomebodyElsesInbox() {
        // The workspace name is customer-chosen, reaches the template verbatim,
        // and is rendered as HTML in a colleague's mail client. This is the one
        // thing in this class that is a security control rather than polish.
        String hostile = "<img src=x onerror=alert(1)>";

        EmailTemplates.Rendered email =
                EmailTemplates.workspaceInvite(hostile, "http://localhost:5173");

        assertFalse(email.html().contains("<img src=x"));
        assertTrue(email.html().contains("&lt;img src=x onerror=alert(1)&gt;"));
    }

    @Test
    void aWorkspaceNameCannotBreakOutOfTheHrefAttribute() {
        String hostile = "\" onmouseover=\"alert(1)";

        String html = EmailTemplates.workspaceInvite(hostile, "http://localhost:5173").html();

        assertFalse(html.contains("onmouseover=\"alert(1)\""));
        assertTrue(html.contains("&quot;"));
    }

    @Test
    void theSubjectCarriesTheWorkspaceNameUnescapedBecauseItIsNotHtml() {
        // Jackson escapes it for the JSON payload; escaping it here too would
        // put "&amp;" in somebody's subject line.
        EmailTemplates.Rendered email =
                EmailTemplates.workspaceInvite("Smith & Co", "http://localhost:5173");

        assertEquals("You've been added to Smith & Co on AutoOps", email.subject());
    }

    @Test
    void templatesRenderIdenticallyOnASecondCall() {
        // They are cached after first load; a mutated cache would show up here.
        EmailTemplates.clearCache();
        String first = EmailTemplates.otpCode("111111", 5).html();
        String second = EmailTemplates.otpCode("111111", 5).html();

        assertEquals(first, second);
    }

    @Test
    void noCarriageReturnsSurviveTheLoad() {
        // These files are checked out CRLF on Windows and LF elsewhere, so
        // without normalising, the bytes mailed depend on who built the image.
        assertFalse(EmailTemplates.otpCode("481920", 5).html().contains("\r"));
    }
}
