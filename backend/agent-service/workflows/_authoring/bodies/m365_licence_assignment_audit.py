"""Who holds a licence, and whether they are still using it. Read-only Graph.

The three facts that decide whether a paid seat is waste live in three places,
and this puts them on one line per user: the SKUs assigned, whether the account
is still enabled, and when the person last actually signed in.

**No prices.** Microsoft Graph does not expose what a tenant pays for a SKU —
it varies by agreement, term and channel. So this reports SEATS and SKU names
and never a currency figure. "11 E5 seats are assigned to disabled accounts" is
something the operator can price exactly; a number invented here would be
confidently wrong.

`signInActivity` needs AuditLog.Read.All and an Entra ID P1/P2 plan. Where it
is absent the field reads `unavailable` rather than being treated as "never
signed in" — the difference decides whether someone's licence gets removed.
"""
import json
import os
import sys
import urllib.parse
from datetime import datetime, timezone

import requests

SCOPE = "{{Scope}}"
INACTIVE_AFTER_DAYS = int("{{InactiveAfterDays}}")

if SCOPE not in ("all", "disabled-only", "licensed-only"):
    sys.exit("Scope %r is not one this audit supports." % SCOPE)
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


def token():
    """A client-credentials token for Graph.

    The failure here is the one worth reporting precisely: a tenant that has
    connected an Azure SUBSCRIPTION rather than an Entra app registration with
    Graph permissions gets a token that works for ARM and returns 403 on every
    Graph call, which reads like a broken automation rather than a setup step.
    """
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


def graph_get(path, access_token, *, tolerate=()):
    """A paged Graph collection, or a named reason it could not be read."""
    url = path if path.startswith("http") else GRAPH + path
    items = []
    while url:
        response = requests.get(
            url, headers={"Authorization": "Bearer " + access_token}, timeout=60
        )
        if response.status_code in tolerate:
            return None, "http_%d" % response.status_code
        if response.status_code == 403:
            body = response.json().get("error", {}).get("message", "")[:200]
            return None, "forbidden(%s)" % (body or "missing Graph permission")
        if response.status_code != 200:
            return None, "http_%d(%s)" % (response.status_code, response.text[:120])
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

    skus, error = graph_get("/subscribedSkus", access_token)
    if error:
        sys.exit("Could not read the tenant's subscriptions: %s. The app registration "
                 "needs Organization.Read.All." % error)

    by_sku = {}
    print("MICROSOFT 365 LICENCE ASSIGNMENT AUDIT")
    print("scope=%s inactive_after_days=%d" % (SCOPE, INACTIVE_AFTER_DAYS))
    print("note=Graph does not expose what this tenant pays for a SKU, so seats are "
          "reported and never a currency amount")
    print("")
    print("SUBSCRIPTIONS")
    idle_seats = 0
    for sku in skus:
        part = sku.get("skuPartNumber", "?")
        by_sku[sku.get("skuId")] = part
        prepaid = sku.get("prepaidUnits", {}).get("enabled", 0)
        consumed = sku.get("consumedUnits", 0)
        spare = prepaid - consumed
        idle_seats += max(spare, 0)
        print("sku=%s prepaid=%d assigned=%d unassigned=%d" % (part, prepaid, consumed, spare))

    select = ",".join([
        "id", "displayName", "userPrincipalName", "accountEnabled",
        "assignedLicenses", "userType", "createdDateTime", "signInActivity",
    ])
    users, error = graph_get("/users?$select=%s&$top=999" % select, access_token)
    if error:
        # signInActivity is the field most likely to be refused, and it is
        # refused for the whole request. Retrying without it yields a usable
        # audit that is explicit about what is missing, which beats failing.
        print("")
        print("sign_in_activity_unavailable=%s (retrying without it)" % error)
        select = select.replace(",signInActivity", "")
        users, error = graph_get("/users?$select=%s&$top=999" % select, access_token)
        if error:
            sys.exit("Could not list users: %s. The app registration needs "
                     "User.Read.All." % error)
        sign_in_readable = False
    else:
        sign_in_readable = True

    print("")
    print("USERS")
    rows = []
    for user in users:
        licences = [by_sku.get(entry.get("skuId"), entry.get("skuId", "?"))
                    for entry in user.get("assignedLicenses", [])]
        if SCOPE == "licensed-only" and not licences:
            continue
        if SCOPE == "disabled-only" and user.get("accountEnabled", True):
            continue

        activity = user.get("signInActivity") or {}
        last = days_since(activity.get("lastSignInDateTime")) if sign_in_readable else None
        if not sign_in_readable:
            last_label = "unavailable"
        elif last is None:
            last_label = "NEVER"
        else:
            last_label = last

        row = {
            "upn": user.get("userPrincipalName", "?"),
            "enabled": "yes" if user.get("accountEnabled", True) else "NO",
            "type": user.get("userType") or "Member",
            "licences": ",".join(sorted(licences)) or "none",
            "licence_count": len(licences),
            "last_sign_in_days": last_label,
            "created_days": days_since(user.get("createdDateTime")),
        }
        rows.append(row)
        print(" ".join("%s=%s" % kv for kv in row.items()))

    licensed = [r for r in rows if r["licence_count"] > 0]
    disabled_licensed = [r for r in licensed if r["enabled"] == "NO"]
    never_signed_in = [r for r in licensed if r["last_sign_in_days"] == "NEVER"]
    dormant = [
        r for r in licensed
        if isinstance(r["last_sign_in_days"], int) and r["last_sign_in_days"] > INACTIVE_AFTER_DAYS
    ]
    guests_licensed = [r for r in licensed if r["type"] == "Guest"]

    def seats(group):
        return sum(r["licence_count"] for r in group)

    print("")
    print("SUMMARY")
    print("users_examined=%d licensed_users=%d" % (len(rows), len(licensed)))
    print("unassigned_paid_seats=%d" % idle_seats)
    print("licensed_but_account_disabled=%d users, %d seats %s"
          % (len(disabled_licensed), seats(disabled_licensed),
             sorted(r["upn"] for r in disabled_licensed)[:20]))
    if sign_in_readable:
        print("licensed_but_never_signed_in=%d users, %d seats %s"
              % (len(never_signed_in), seats(never_signed_in),
                 sorted(r["upn"] for r in never_signed_in)[:20]))
        print("licensed_but_dormant_over_%dd=%d users, %d seats %s"
              % (INACTIVE_AFTER_DAYS, len(dormant), seats(dormant),
                 sorted(r["upn"] for r in dormant)[:20]))
    else:
        # Named, not silently omitted. An offboarding report that quietly drops
        # the dormancy check reads as "everyone is active".
        print("licensed_but_never_signed_in=unavailable (sign-in activity could not be read)")
        print("licensed_but_dormant=unavailable (sign-in activity could not be read)")
    print("licensed_guest_accounts=%d %s"
          % (len(guests_licensed), sorted(r["upn"] for r in guests_licensed)[:20]))
    print("")
    print("JSON " + json.dumps({
        "scope": SCOPE,
        "sign_in_activity_readable": sign_in_readable,
        "unassigned_paid_seats": idle_seats,
        "subscriptions": [
            {"sku": by_sku.get(s.get("skuId")),
             "prepaid": s.get("prepaidUnits", {}).get("enabled", 0),
             "assigned": s.get("consumedUnits", 0)}
            for s in skus
        ],
        "users": rows,
    }, sort_keys=True, default=str))


main()
