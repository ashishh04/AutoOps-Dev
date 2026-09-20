"""Agents that reason about the WORKSPACE rather than about a vendor's estate.

Every other agent in this build reaches a cloud: boto3 for AWS, Graph for
Microsoft 365. Those answer questions about that vendor, they need that vendor's
credentials, and a customer running three of them needs three agents that cannot
see each other's evidence.

These read what AutoOps already holds — the automations that ran here, what they
returned, which failed and whether they failed together, which changes are parked
waiting for a human. That evidence needs no customer credential and has the same
shape whether the estate is AWS, on-premises VMware, or both at once, because all
of it was automated through one control plane.

It is also the only evidence in the platform that no competitor can copy by
writing a better script: it exists because the automation ran here.
"""
