package com.intertec.autoops.auth.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The transactional emails, rendered from templates in {@code resources/email/}.
 *
 * <p><b>Why not a template engine.</b> Two emails do not justify Thymeleaf plus
 * its autoconfiguration and startup cost. What they do justify is escaping,
 * which is the only part of a template engine that matters here — see below.
 *
 * <p><b>Every email is sent as HTML and text together</b>, not one or the other.
 * The text part is what a screen reader, a terminal client and a spam filter
 * read; an HTML-only message scores worse and is unreadable in some places. The
 * two parts must say the same thing, which is why they live side by side in the
 * same folder and are rendered from the same values.
 */
public final class EmailTemplates {

    private static final String LAYOUT = "layout.html";
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    /** One email, ready to send. */
    public record Rendered(String subject, String html, String text) {
    }

    private EmailTemplates() {
    }

    public static Rendered otpCode(String code, long ttlMinutes) {
        String ttl = ttlMinutes == 1 ? "1 minute" : ttlMinutes + " minutes";
        Map<String, String> values = Map.of("code", code, "ttl", ttl);
        return new Rendered(
                "Your AutoOps verification code",
                layout("Your AutoOps verification code",
                        // The preview line carries the code itself: on a phone
                        // the notification is often all someone needs to see.
                        code + " is your AutoOps verification code. It expires in " + ttl + ".",
                        render("otp-code.html", values),
                        "You're receiving this because someone asked for a sign-in code "
                                + "for this email address."),
                render("otp-code.txt", values));
    }

    public static Rendered workspaceInvite(String workspaceName, String appBaseUrl) {
        Map<String, String> values = Map.of(
                "workspace", workspaceName,
                "signInUrl", stripTrailingSlash(appBaseUrl) + "/login");
        return new Rendered(
                "You've been added to " + workspaceName + " on AutoOps",
                layout("You've been added to " + workspaceName,
                        "Sign in with this email address — no password needed.",
                        render("workspace-invite.html", values),
                        "You're receiving this because an administrator added this email "
                                + "address to a workspace."),
                render("workspace-invite.txt", values));
    }

    /**
     * Wraps rendered content in the shared shell.
     *
     * <p>{@code content} is the one slot inserted raw, and it is always output
     * this method's own {@link #render} produced — never anything that came
     * from a caller. That is the whole reason layout composition happens here
     * in Java rather than through an {@code include} in the template: a
     * template language with a raw-output directive is a template language
     * where somebody eventually points it at a workspace name.
     */
    private static String layout(String title, String preheader, String content, String footer) {
        return load(LAYOUT)
                .replace("{{title}}", escape(title))
                .replace("{{preheader}}", escape(preheader))
                .replace("{{footer}}", escape(footer))
                .replace("{{content}}", content);
    }

    /**
     * Substitutes {@code {{key}}} placeholders, escaping every value.
     *
     * <p>Escaping is not optional. {@code workspaceName} is chosen by the
     * customer, reaches this method verbatim, and lands in an HTML document in
     * somebody else's inbox — a workspace called
     * {@code <img src=x onerror=...>} is the whole attack, and mail clients
     * that strip scripts do not all strip everything.
     *
     * <p>The {@code .txt} templates go through the same path with escaping
     * applied, which is harmless: an ampersand in a workspace name renders as
     * {@code &amp;} in the plain-text part. Left deliberately rather than
     * special-cased, because a second code path is a second place for the
     * escaping to be forgotten. (Worth revisiting only if a customer name ever
     * reads badly enough to notice.)
     */
    static String render(String template, Map<String, String> values) {
        String out = load(template);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}", escape(entry.getValue()));
        }
        return out;
    }

    private static String load(String name) {
        return CACHE.computeIfAbsent(name, key -> {
            try (InputStream in = EmailTemplates.class
                    .getResourceAsStream("/email/" + key)) {
                if (in == null) {
                    // A missing template is a packaging error, and it must fail
                    // loudly at first send rather than mail an empty body.
                    throw new IllegalStateException("Email template not found: " + key);
                }
                // `* text=auto` in .gitattributes checks these out CRLF on
                // Windows and LF elsewhere; normalising keeps the rendered
                // bytes identical whoever built the image.
                return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n");
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String stripTrailingSlash(String url) {
        String trimmed = url == null ? "" : url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /** Test seam: templates are cached for the JVM's life. */
    static void clearCache() {
        CACHE.clear();
    }
}
