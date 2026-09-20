"""Microsoft 365 and Entra ID agents.

These reach Microsoft Graph over REST from a ``pyscript`` step, using the
``AZURE_TENANT_ID`` / ``AZURE_CLIENT_ID`` / ``AZURE_CLIENT_SECRET`` triple that
job-service already puts in a step's environment for an Azure connection —
which happens to be exactly Graph's client-credentials contract. No new
credential plumbing, no PowerShell modules, no WinRM.

That matters for what this platform can cover. The transport, not the reasoning,
is what has limited these agents to cloud accounts: Active Directory, Windows
Server, Exchange on-premises and VMware are all blocked on a credential a job
step cannot yet be handed, or on a runner that does not exist. Identity, licences
and mailboxes are the managed-services business, and they are reachable today.
"""
