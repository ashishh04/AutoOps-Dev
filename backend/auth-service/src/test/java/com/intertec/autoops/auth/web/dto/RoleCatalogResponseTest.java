package com.intertec.autoops.auth.web.dto;

import com.intertec.autoops.auth.domain.User;
import com.intertec.autoops.auth.domain.UserRole;
import com.intertec.autoops.auth.domain.UserStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The role catalog documents real enforcement: counts come from the live
 * roster (DISABLED excluded), PROVIDER appears only when present, and the
 * grants mirror the admin-only checks across the services.
 */
class RoleCatalogResponseTest {

    private static User user(UserRole role, UserStatus status) {
        User user = new User();
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    /**
     * By CODE, never by position.
     *
     * <p>This is why both tests in this file were red. They read roles as
     * {@code roles().get(0)}, {@code get(1)}, {@code get(2)} — and when VIEWER
     * was added to the catalog every index shifted, so "provider" became the
     * viewer and its billing grant became false. The failure named a permission
     * rather than the row it had actually read, which is what made it look like
     * a permissions bug rather than an off-by-one.
     *
     * <p>Position is not part of the contract; the code is. Looking up by code
     * means adding a role can never silently repoint an assertion at a
     * different one again.
     */
    private static RoleCatalogResponse.RoleInfo role(RoleCatalogResponse catalog, String code) {
        return catalog.roles().stream()
                .filter(r -> r.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no " + code + " row; catalog holds "
                                + catalog.roles().stream().map(RoleCatalogResponse.RoleInfo::code)
                                        .toList()));
    }

    @Test
    void countsLiveMembersAndHidesEmptyProviderRole() {
        RoleCatalogResponse catalog = RoleCatalogResponse.forTenant(List.of(
                user(UserRole.ADMIN, UserStatus.ACTIVE),
                user(UserRole.CLIENT, UserStatus.ACTIVE),
                user(UserRole.CLIENT, UserStatus.PENDING),
                user(UserRole.CLIENT, UserStatus.DISABLED)));

        // The actual claim, stated directly rather than as a row count. The old
        // assertion was `size() == 2`, which was only ever a proxy for it and
        // broke the moment a fourth fixed role existed.
        assertTrue(catalog.roles().stream().noneMatch(r -> r.code().equals("PROVIDER")),
                "no PROVIDER row without provider accounts");

        // ADMIN, Operator and Viewer are FIXED rows: they are the platform's
        // roles whether or not anyone holds them, so an empty workspace still
        // shows what the roles would mean.
        assertEquals(List.of("ADMIN", "CLIENT", "VIEWER"),
                catalog.roles().stream().map(RoleCatalogResponse.RoleInfo::code).toList());

        assertEquals(1, role(catalog, "ADMIN").members());
        assertEquals(2, role(catalog, "CLIENT").members(),
                "PENDING counts, DISABLED does not");
        assertEquals(0, role(catalog, "VIEWER").members());
        assertEquals(9, catalog.permissions().size());
    }

    @Test
    void grantsMirrorTheEnforcedChecks() {
        RoleCatalogResponse catalog = RoleCatalogResponse.forTenant(List.of(
                user(UserRole.PROVIDER, UserStatus.ACTIVE)));

        RoleCatalogResponse.RoleInfo admin = role(catalog, "ADMIN");
        RoleCatalogResponse.RoleInfo operator = role(catalog, "CLIENT");
        RoleCatalogResponse.RoleInfo provider = role(catalog, "PROVIDER");

        assertTrue(admin.grants().values().stream().allMatch(Boolean::booleanValue));
        assertFalse(operator.grants().get("manageMembers"));
        assertFalse(operator.grants().get("approveRuns"));
        assertTrue(operator.grants().get("editAutomations"));
        assertTrue(operator.grants().get("runAutomations"));
        // subscription-service accepts ADMIN|PROVIDER on subscribe/cancel/retry.
        assertTrue(provider.grants().get("manageBilling"));
        // Member management is auth-service's requireAdmin, which PROVIDER is not.
        assertFalse(provider.grants().get("manageMembers"));
        assertEquals(1, provider.members());
    }

    @Test
    void aViewerHoldsNoGrantAtAll() {
        // core-service refuses every mutating method for the VIEWER claim at the
        // edge, before it reaches a controller. A single true here would be the
        // catalog telling a customer they can do something the platform blocks.
        RoleCatalogResponse catalog =
                RoleCatalogResponse.forTenant(List.of(user(UserRole.VIEWER, UserStatus.ACTIVE)));

        assertTrue(role(catalog, "VIEWER").grants().values().stream()
                .noneMatch(Boolean::booleanValue));
        assertEquals(1, role(catalog, "VIEWER").members());
    }
}