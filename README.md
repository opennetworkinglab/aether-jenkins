# aether-jenkins

This repo contains Jenkins pipelines for testing OnRamp blueprints.
Each file (a Groovy script) defines an integration test for one of the
blueprints documented
[here](https://docs.aetherproject.org/master/onramp/blueprints.html).

The pipelines are executed daily, with each pipeline parameterized to
run in multiple jobs (e.g., the `${AgentLabel}` parameter selects the
Ubuntu release being tested). Outcomes can be viewed on Aether's
[Jenkins server](https://jenkins.aetherproject.org/view/OnRamp_Blueprints/)).





