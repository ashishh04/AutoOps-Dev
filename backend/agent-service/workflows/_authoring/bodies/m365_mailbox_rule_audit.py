"""Inbox rules that send mail out of the organisation. Read-only Graph.

This is the one that finds a live data leak rather than a posture weakness. Two
things produce an external forwarding rule and they look identical in the data:

* a leaver who set one up before they went, which is an exfiltration path still
  running months after their account was supposed to be closed;
* business email compromise, where an attacker adds a rule that forwards or
  redirects finance mail to an address they control and deletes the original,
  so nobody in the company ever sees the thread.

The rule that hides its tracks is the tell. A rule that forwards externally AND
marks as read or moves to Deleted Items is doing something a person would not
do to their own mailbox, and that is called out separately.

**"External" is computed from the tenant's own verified domains**, read from
Graph, not from a list typed into a form. A hand-maintained internal-domain list
is wrong the first time the customer adds a domain, and being wrong here means
either missing a real leak or drowning the report in false positives.
"""
import json
import os
import re
import sys
import urllib.parse

import requests

SCOPE = "{{Scope}}"
USER_LIST = "{{UserPrincipalNames}}"
EXTRA_INTERNAL = "{{AdditionalInternalDomains}}"
MAX_USERS = int("{{MaxUsers}}")

if SCOPE not in ("disabled-accounts", "named-users", "all-users"):
    sys.exit("Scope %r is not one this audit supports." % SCOPE)
if not 1 <= MAX_USERS <= 2000:
    sys.exit("MaxUsers %d is outside 1-2000." % MAX_USERS)
if USER_LIST and not re.fullmatch(r"[A-Za-z0-9._%+\-@]+(,[A-Za-z0-9._%+\-@]+)*", USER_LIST):
    sys.exit("UserPrincipalNames must be a comma-separated list of addresses.")
if EXTRA_INTERNAL and not re.fullmatch(r"[a-z0-9.\-]+(,[a-z0-9.\-]+)*", EXTRA_INTERNAL):
    sys.exit("AdditionalInternalDomains must be a comma-separated list of domains.")
if SCOPE == "named-users" and not USER_LIST:
    sys.exit("Scope is named-users but no UserPrincipalNames were supplied.")

GRAPH = "https://graph.microsoft.com/v1.0"
TENANT = os.environ.get("AZURE_TENANT_ID", "")
CLIENT = os.environ.get("AZURE_CLIENT_ID", "")
SECRET = os.environ.get("AZURE_CLIENT_SECRET", "")

if not (TENANT and CLIENT and SECRET):
    sys.exit(
        "No Microsoft 365 credentials reached this step. Connect an Azure/Entra "
        "application to this project — it needs a tenant id, client id and client secret."
    )

#: Rule actions that move mail out of the organisation.
OUTBOUND = ("forwardTo", "redirectTo", "forwardAsAttachmentTo")
#: Rule actions whose only purpose, combined with an outbound action, is to stop
#: the mailbox owner noticing.
CONCEALING = ("delete", "markAsRead", "moveToFolder", "permanentDelete")


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


def graph_get(path, access_token, *, single=False):
    url = path if path.startswith("http") else GRAPH + path
    items = []
    while url:
        response = requests.get(
            url, headers={"Authorization": "Bearer " + access_token}, timeout=60
        )
        if response.status_code == 403:
            return None, "forbidden"
        if response.status_code == 404:
            # No mailbox — an unlicensed or on-premises account. Not an error.
            return None, "no_mailbox"
        if response.status_code != 200:
            return None, "http_%d" % response.status_code
        payload = response.json()
        if single:
            return payload, None
        items.extend(payload.get("value", []))
        url = payload.get("@odata.nextLink")
    return items, None


def addresses_in(action_value):
    """Recipient addresses out of a rule action, whatever shape it arrived in."""
    found = []
    for entry in action_value or []:
        address = (entry or {}).get("emailAddress", {}).get("address")
        if address:
            found.append(address.lower())
    return found


def main():
    access_token = token()

    organisation, error = graph_get("/organization", access_token)
    if error:
        sys.exit("Could not read the tenant's verified domains: %s. The app "
                 "registration needs Organization.Read.All." % error)
    internal = {
        domain.get("name", "").lower()
        for org in (organisation or [])
        for domain in org.get("verifiedDomains", [])
        if domain.get("name")
    }
    internal |= {d for d in EXTRA_INTERNAL.split(",") if d}

    if SCOPE == "named-users":
        wanted = [name.strip().lower() for name in USER_LIST.split(",") if name.strip()]
        users = [{"userPrincipalName": name, "id": name} for name in wanted]
    else:
        query = "/users?$select=id,userPrincipalName,accountEnabled&$top=999"
        everyone, error = graph_get(query, access_token)
        if error:
            sys.exit("Could not list users: %s. The app registration needs "
                     "User.Read.All." % error)
        users = [
            u for u in everyone
            if SCOPE == "all-users" or not u.get("accountEnabled", True)
        ]

    truncated = len(users) > MAX_USERS
    users = users[:MAX_USERS]

    print("MICROSOFT 365 MAILBOX RULE AUDIT")
    print("scope=%s mailboxes_examined=%d" % (SCOPE, len(users)))
    print("internal_domains=%s" % ",".join(sorted(internal)))
    if truncated:
        print("truncated=yes (more mailboxes matched than MaxUsers; narrow the scope "
              "or raise the cap before treating this as complete)")
    print("")

    findings = []
    unreadable = []
    for user in users:
        upn = user.get("userPrincipalName", "?")
        rules, error = graph_get(
            "/users/%s/mailFolders/inbox/messageRules" % urllib.parse.quote(upn), access_token
        )
        if error:
            if error != "no_mailbox":
                unreadable.append("%s:%s" % (upn, error))
            continue

        for rule in rules or []:
            actions = rule.get("actions", {}) or {}
            recipients = []
            for kind in OUTBOUND:
                recipients.extend(addresses_in(actions.get(kind)))
            if not recipients:
                continue

            external = sorted({
                address for address in recipients
                if address.rsplit("@", 1)[-1] not in internal
            })
            if not external:
                continue

            conceals = [kind for kind in CONCEALING if actions.get(kind)]
            finding = {
                "upn": upn,
                "account_enabled": "yes" if user.get("accountEnabled", True) else "NO",
                "rule": (rule.get("displayName") or "(unnamed)")[:48],
                "rule_enabled": "yes" if rule.get("isEnabled") else "no",
                "forwards_to": ",".join(external),
                "hides_itself": ",".join(conceals) or "no",
            }
            findings.append(finding)
            print(" ".join("%s=%s" % kv for kv in finding.items()))

    active = [f for f in findings if f["rule_enabled"] == "yes"]
    concealing = [f for f in active if f["hides_itself"] != "no"]
    on_disabled = [f for f in active if f["account_enabled"] == "NO"]

    print("")
    print("SUMMARY")
    print("mailboxes_examined=%d" % len(users))
    print("external_forwarding_rules=%d (of which enabled: %d)" % (len(findings), len(active)))
    print("enabled_rules_that_also_hide_themselves=%d %s"
          % (len(concealing), sorted({f["upn"] for f in concealing})))
    print("enabled_rules_on_DISABLED_accounts=%d %s"
          % (len(on_disabled), sorted({f["upn"] for f in on_disabled})))
    if unreadable:
        print("mailboxes_that_could_not_be_read=%d %s" % (len(unreadable), unreadable[:20]))
    print("")
    print("JSON " + json.dumps({
        "scope": SCOPE,
        "internal_domains": sorted(internal),
        "truncated": truncated,
        "rules": findings,
        "unreadable": unreadable,
    }, sort_keys=True))


main()
