# aether-jenkins

This repo contains Jenkins pipelines for testing OnRamp blueprints.
Each file (a Groovy script) defines an integration test for one of the
blueprints documented in the
[Aether OnRamp Guide](https://docs.aetherproject.org/master/onramp/blueprints.html).

The pipelines are executed daily, with each pipeline parameterized to
run in multiple jobs. The `${AgentLabel}` parameter selects the
Ubuntu release being tested (currently ``20.04`` and ``22.04``),
with all jobs running in AWS VMs (currently resourced as `M7iFlex2xlarge`).
Pipelines that exercise two-server tests (e.g., `ueransim.groovy`, `upf.groovy`,
and `gnbsim.groovy` run in VMs that have the
[AWS CLI](https://aws.amazon.com/cli/) installed; the CLI is is used to create
the second VM. All VMs have Ansible installed, as documented in the
[OnRamp Guide](https://docs.aetherproject.org/master/onramp/start.html#prep-environment).
Outcomes can be viewed on Aether's
[Jenkins server](https://jenkins.aetherproject.org).





