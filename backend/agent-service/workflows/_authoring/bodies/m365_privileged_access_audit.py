"""Who can do the most damage, and how weakly are they protected. Read-only Graph.

Directory role membership on its own is an org chart. What makes it a security
finding is the combination: a Global Administrator who has no MFA registered, or
who is a guest from another tenant, or who has not signed in for six months and
would not be noticed if someone else started using the account.

MFA registration is read from the tenant-wide registration report — one call for
the whole directory — rather than by walking every privileged user's
authentication methods. That is a real difference in a large tenant: one request
against potentially hundreds, and no chance of the report timing out halfway
through and being read as "these admins have no MFA".

**Registered is not the same as enforced.** Someone can have an authenticator
registered and still be exempt from the Conditional Access policy that would
demand it. Graph's registration report cannot see policy assignment, so this
reports registration and says so; a report claiming "MFA is enforced" from this
data would be wrong in exactly the tenants where it matters.
"""
import json
import os
import sys
import urllib.parse
from datetime import datetime, timezone

import requests

INACTIVE_AFTER_DAYS = int("{{InactiveAfterDays}}")

if not 1 <= INACTIVE_AFTER_DAYS <= 3650:
    sys.exit("InactiveAfterDays %d is outside 1-3650." % INACTIVE_AFTER_DAYS)

GRAPH = "https://graph.microsoft.com/v1.0"
NOW = datetime.now(timezone.utc)

TENANT = os.environ.get("AZURE_TENANT_ID", "")
CLIENT = os.environ.get("AZURE_CLIENT_ID", "")
SECRET = os.environ.get("AZURE_CLIENT_SECRET", "")

if not (TENANT and CLIENT and SECRET):
    sys.exit(
        "No Microsoft 365 credentials reached this step. Connect an Azure/Entra "
        "application to this project — it needs a tenant id, client id and client secret."
    )

#: Roles whose holder can grant themselves anything else. A finding on one of
#: these outranks the same finding on a service-desk role, and the report should
#: not make a reader work that out from the role name.
TIER_ZERO = {
    "Global Administrator",
    "Privileged Role Administrator",
    "Privileged Authentication Administrator",
    "Application Administrator",
    "Cloud Application Administrator",
    "Hybrid Identity Administrator",
    "Partner Tier2 Support",
    "Domain Name Administrator",
}


def token():
    response = requests.post(
        "https://login.microsoftonline.com/%s/oauth2/v2.0/token" % urllib.parse.quote(TENANT),
        data={
            "client_id": CLIENT,
            "client_secret": SECRET,
            "grant_type": "client_credentials",
            "scope": "https://graph.microsoft.com/.default",
        },
        timeout=30,
    )
    if response.status_code != 200:
        detail = response.json().get("error_description", response.text)[:300]
        sys.exit("Could not authenticate to Microsoft Graph: %s" % detail)
    return response.json()["access_token"]


def graph_get(path, access_token):
    url = path if path.startswith("http") else GRAPH + path
    items = []
    while url:
        response = requests.get(
            url, headers={"Authorization": "Bearer " + access_token}, timeout=60
        )
        if response.status_code == 403:
            return None, "forbidden"
        if response.status_code != 200:
            return None, "http_%d" % response.status_code
        payload = response.json()
        items.extend(payload.get("value", []))
        url = payload.get("@odata.nextLink")
    return items, None


def days_since(stamp):
    if not stamp:
        return None
    try:
        return int((NOW - datetime.fromisoformat(stamp.replace("Z", "+00:00"))).total_seconds() // 86400)
    except ValueError:
        return None


def main():
    access_token = token()

    roles, error = graph_get("/directoryRoles", access_token)
    if error:
        sys.exit("Could not list directory roles: %s. The app registration needs "
                 "RoleManagement.Read.Directory." % error)

    # One call for the whole tenant, rather than one per privileged user.
    registration, mfa_error = graph_get(
        "/reports/authenticationMethods/userRegistrationDetails", access_token
    )
    mfa_by_id = {}
    if not mfa_error:
        mfa_by_id = {entry.get("id"): entry for entry in registration or []}

    print("MICROSOFT 365 PRIVILEGED ACCESS AUDIT")
    print("directory_roles_with_members=%d inactive_after_days=%d"
          % (len(roles), INACTIVE_AFTER_DAYS))
    print("note=this reports whether strong authentication is REGISTERED. Graph cannot "
          "show whether a Conditional Access policy actually enforces it.")
    if mfa_error:
        print("mfa_registration_unavailable=%s (the app registration needs "
              "AuditLog.Read.All)" % mfa_error)
    print("")

    holders = {}
    failures = []
    for role in roles:
        name = role.get("displayName", "?")
        members, error = graph_get("/directoryRoles/%s/members" % role.get("id"), access_token)
        if error:
            failures.append("%s:%s" % (name, error))
            continue
        for member in members or []:
            if member.get("@odata.type", "").endswith("servicePrincipal"):
                # An app with a directory role is a real risk and a different
                # report: it has no MFA, no sign-in activity and no human owner,
                # so mixing it into these counts would be misleading.
                holders.setdefault("sp:" + member.get("id"), {
                    "kind": "servicePrincipal",
                    "name": member.get("displayName", "?"),
                    "roles": set(),
                })["roles"].add(name)
                continue
            entry = holders.setdefault(member.get("id"), {
                "kind": "user",
                "name": member.get("displayName", "?"),
                "upn": member.get("userPrincipalName", "?"),
                "roles": set(),
            })
            entry["roles"].add(name)

    select = "id,userPrincipalName,accountEnabled,userType,signInActivity"
    users, users_error = graph_get("/users?$select=%s&$top=999" % select, access_token)
    if users_error:
        select = select.replace(",signInActivity", "")
        users, users_error = graph_get("/users?$select=%s&$top=999" % select, access_token)
        sign_in_readable = False
    else:
        sign_in_readable = True
    by_id = {u.get("id"): u for u in (users or [])}

    print("PRIVILEGED PRINCIPALS")
    rows = []
    for principal_id, entry in sorted(holders.items(), key=lambda kv: kv[1]["name"]):
        if entry["kind"] == "servicePrincipal":
            row = {
                "principal": entry["name"],
                "kind": "servicePrincipal",
                "roles": ";".join(sorted(entry["roles"])),
                "tier_zero": "YES" if entry["roles"] & TIER_ZERO else "no",
                "mfa_registered": "n/a",
                "enabled": "n/a",
                "last_sign_in_days": "n/a",
            }
            rows.append(row)
            print(" ".join("%s=%s" % kv for kv in row.items()))
            continue

        user = by_id.get(principal_id, {})
        activity = user.get("signInActivity") or {}
        last = days_since(activity.get("lastSignInDateTime")) if sign_in_readable else None
        registered = mfa_by_id.get(principal_id)
        if mfa_error:
            mfa = "unavailable"
        elif registered is None:
            mfa = "unknown"
        else:
            mfa = "yes" if registered.get("isMfaRegistered") else "NO"

        row = {
            "principal": entry.get("upn", entry["name"]),
            "kind": "Guest" if user.get("userType") == "Guest" else "user",
            "roles": ";".join(sorted(entry["roles"])),
            "tier_zero": "YES" if entry["roles"] & TIER_ZERO else "no",
            "mfa_registered": mfa,
            "enabled": "yes" if user.get("accountEnabled", True) else "NO",
            "last_sign_in_days": (
                "unavailable" if not sign_in_readable else ("NEVER" if last is None else last)
            ),
        }
        rows.append(row)
        print(" ".join("%s=%s" % kv for kv in row.items()))

    people = [r for r in rows if r["kind"] != "servicePrincipal"]
    tier_zero = [r for r in people if r["tier_zero"] == "YES"]
    no_mfa = [r for r in people if r["mfa_registered"] == "NO"]
    tier_zero_no_mfa = [r for r in tier_zero if r["mfa_registered"] == "NO"]
    guests = [r for r in people if r["kind"] == "Guest"]
    dormant = [
        r for r in people
        if isinstance(r["last_sign_in_days"], int) and r["last_sign_in_days"] > INACTIVE_AFTER_DAYS
    ]
    disabled = [r for r in people if r["enabled"] == "NO"]
    service_principals = [r for r in rows if r["kind"] == "servicePrincipal"]

    print("")
    print("SUMMARY")
    print("privileged_users=%d privileged_service_principals=%d"
          % (len(people), len(service_principals)))
    print("tier_zero_role_holders=%d %s"
          % (len(tier_zero), sorted(r["principal"] for r in tier_zero)[:20]))
    print("privileged_without_mfa_registered=%d %s"
          % (len(no_mfa), sorted(r["principal"] for r in no_mfa)[:20]))
    print("TIER_ZERO_without_mfa_registered=%d %s"
          % (len(tier_zero_no_mfa), sorted(r["principal"] for r in tier_zero_no_mfa)[:20]))
    print("privileged_guest_accounts=%d %s"
          % (len(guests), sorted(r["principal"] for r in guests)[:20]))
    print("privileged_dormant_over_%dd=%d %s"
          % (INACTIVE_AFTER_DAYS, len(dormant), sorted(r["principal"] for r in dormant)[:20]))
    print("privileged_but_account_disabled=%d %s"
          % (len(disabled), sorted(r["principal"] for r in disabled)[:20]))
    if failures:
        print("roles_whose_members_could_not_be_read=%s" % failures[:10])
    print("")
    print("JSON " + json.dumps({
        "mfa_registration_readable": not mfa_error,
        "sign_in_activity_readable": sign_in_readable,
        "principals": rows,
        "failures": failures,
    }, sort_keys=True, default=str))


main()
