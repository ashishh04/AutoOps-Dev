package com.intertec.autoops.core.web;

import com.intertec.autoops.core.domain.CoreAuditEventType;
import com.intertec.autoops.core.domain.LibraryItem;
import com.intertec.autoops.core.exception.CoreException;
import com.intertec.autoops.core.service.AuditService;
import com.intertec.autoops.core.service.LibraryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Template catalog + the tenant's imports. Cloning a premium template is
 * gated on PREMIUM_TEMPLATES; writing and editing your own scripts needs only
 * a live subscription.
 */
@RestController
@RequestMapping("/api/library")
public class LibraryController {

    private final LibraryService libraryService;
    private final AuditService auditService;

    public LibraryController(LibraryService libraryService, AuditService auditService) {
        this.libraryService = libraryService;
        this.auditService = auditService;
    }

    /**
     * @param installs times a SCRIPT has been imported into a workspace — a
     *                 counter, incremented by LibraryService.clone().
     * @param rollouts how many customer copies of a WORKFLOW or AGENT exist
     *                 right now. Counted live from the services that hold them,
     *                 so revoking a delivery takes it back down. Zero for
     *                 everyone but a PROVIDER: it discloses how many customers
     *                 hold each template, which is the provider's business and
     *                 not a tenant's.
     */
    public record LibraryItemResponse(Long id, String title, String description, String type,
                                      String category, boolean premium, boolean managed,
                                      boolean owned, boolean locked, int installs,
                                      long rollouts, String definition) {

        static LibraryItemResponse from(LibraryService.LibraryView view,
                                        Map<String, Long> rolloutCounts) {
            return from(view, rolloutCounts, true);
        }

        /**
         * @param withDefinition whether to ship the template body. The catalog
         *        is ~240 items averaging 8.6KB of JSON each, so including it
         *        makes the list response about two megabytes — every time the
         *        Library page is opened. The customer's Library never reads it;
         *        only the provider's screen does, to show an agent's spec. So
         *        the list carries it only for the screen that uses it, and the
         *        rest of the payload is unchanged.
         */
        static LibraryItemResponse from(LibraryService.LibraryView view,
                                        Map<String, Long> rolloutCounts,
                                        boolean withDefinition) {
            LibraryItem i = view.item();
            return new LibraryItemResponse(i.getId(), i.getTitle(), i.getDescription(),
                    i.getType().name().toLowerCase(Locale.ROOT), i.getCategory(),
                    i.isPremium(), view.managed(), view.owned(), view.locked(),
                    i.getInstalls(),
                    rolloutCounts.getOrDefault(String.valueOf(i.getId()), 0L),
                    // Definitions of LOCKED premium templates stay server-side.
                    withDefinition && !view.locked() ? i.getDefinition() : null);
        }

        static LibraryItemResponse from(LibraryService.LibraryView view) {
            return from(view, Map.of());
        }
    }

    public record CreateTemplateRequest(@NotBlank @Size(max = 128) String title,
                                        @Size(max = 512) String description,
                                        @Size(max = 16) String type,
                                        @Size(max = 64) String category,
                                        @NotBlank String definition) {
    }

    /**
     * The catalog plus this workspace's own copies.
     *
     * <p>Serves the provider's library screen too, which is why the rollout
     * count is fetched here — and only for a PROVIDER. Asking for it on every
     * tenant's library page would put two cross-service calls on a hot read and
     * disclose the provider's per-template customer counts to customers.
     */
    @GetMapping
    public List<LibraryItemResponse> list(@AuthenticationPrincipal Jwt jwt) {
        boolean provider = "PROVIDER".equals(jwt.getClaimAsString("role"));
        Map<String, Long> rollouts = provider ? libraryService.rolloutCounts() : Map.of();
        return libraryService.list(tenant(jwt), jwt.getTokenValue()).stream()
                .map(view -> LibraryItemResponse.from(view, rollouts, provider)).toList();
    }

    @PostMapping("/{id}/clone")
    @ResponseStatus(HttpStatus.CREATED)
    public LibraryItemResponse clone(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        LibraryItem copy = libraryService.clone(tenant(jwt), jwt.getSubject(),
                jwt.getTokenValue(), id);
        auditService.record(CoreAuditEventType.LIBRARY_CLONED, tenant(jwt), jwt.getSubject(),
                null, "TEMPLATE", copy.getId(), copy.getTitle(), "from catalog item " + id);
        return new LibraryItemResponse(copy.getId(), copy.getTitle(), copy.getDescription(),
                copy.getType().name().toLowerCase(Locale.ROOT), copy.getCategory(),
                copy.isPremium(), false, true, false, copy.getInstalls(),
                // A workspace's own copy is not a catalog item, so nothing was
                // ever rolled out FROM it.
                0L, copy.getDefinition());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LibraryItemResponse create(@Valid @RequestBody CreateTemplateRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        LibraryItem item = libraryService.createOwn(tenant(jwt), jwt.getSubject(),
                jwt.getTokenValue(), request.title(), request.description(),
                request.type(), request.category(), request.definition());
        auditService.record(CoreAuditEventType.LIBRARY_CREATED, tenant(jwt), jwt.getSubject(),
                null, "TEMPLATE", item.getId(), item.getTitle(), null);
        return new LibraryItemResponse(item.getId(), item.getTitle(), item.getDescription(),
                item.getType().name().toLowerCase(Locale.ROOT), item.getCategory(),
                item.isPremium(), false, true, false, item.getInstalls(),
                0L, item.getDefinition());
    }

    /** Every field optional — a rename need not resend the whole script. */
    public record UpdateTemplateRequest(@Size(max = 128) String title,
                                        @Size(max = 512) String description,
                                        @Size(max = 64) String category,
                                        String definition) {
    }

    /**
     * Edit a script this workspace owns — authored here, or imported from the
     * catalog and since adapted. The tenant claim decides which rows are
     * reachable, so a catalog item can never be edited through this path.
     */
    @PutMapping("/{id}")
    public LibraryItemResponse update(@PathVariable Long id,
                                      @Valid @RequestBody UpdateTemplateRequest request,
                                      @AuthenticationPrincipal Jwt jwt) {
        LibraryItem item = libraryService.update(tenant(jwt), jwt.getTokenValue(), id,
                request.title(), request.description(), request.category(),
                request.definition());
        auditService.record(CoreAuditEventType.LIBRARY_UPDATED, tenant(jwt), jwt.getSubject(),
                null, "TEMPLATE", item.getId(), item.getTitle(), null);
        return new LibraryItemResponse(item.getId(), item.getTitle(), item.getDescription(),
                item.getType().name().toLowerCase(Locale.ROOT), item.getCategory(),
                item.isPremium(), false, true, false, item.getInstalls(),
                0L, item.getDefinition());
    }

    private String tenant(Jwt jwt) {
        String tenantId = jwt.getClaimAsString("tenantId");
        if (tenantId == null || tenantId.isBlank()) {
            throw CoreException.badRequest("missing_tenant", "Token has no tenantId claim");
        }
        return tenantId;
    }
}
