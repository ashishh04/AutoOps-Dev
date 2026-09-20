"""AWS agents.

Every agent in this package reaches AWS through published catalog workflows
whose steps are ``pyscript`` — boto3 running on the execution host with the
tenant's own cloud credential in the environment. That is a deliberate choice
over SSH or PowerShell: it is the transport that actually works in the deployed
stack today, and an agent whose tool cannot run is not an agent.
"""
