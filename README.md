# aether-jenkins

This repo defines Jenkins Pipelines for testing OnRamp blueprints.
Each file (a Groovy script) defines an integration test for an
blueprint, some with optional qualifiers. The blueprints are documented
[here](https://docs.aetherproject.org/master/onramp/blueprints.html),
but each pipeline also effectively documents (in executable code) how
the blueprint is deployed and validated.

The pipelines are executed daily at https://jenkins.aetherproject.org,
with each pipeline is parameterized to run in multiple jobs (e.g., the
`${AgentLabel}` parameter selects the Ubuntu release running on the
target agent. Outcomes can be viewed on the Jenkins server,
named according to the following convention:

* AetherOnRamp\_[2srvr\_]Blueprint\_[Qualifier\_]UbuntuRelease
* If *2srvr* is not present, the test runs on a single server.
* If *Qualifier* is not present, the default settings are used.




