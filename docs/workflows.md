# Practical workflows

[한국어](workflows.ko.md) · [Documentation index](guide.md)

The examples use a fictional **Harbor Response Drill** project and its **1.2.0** version. The deliverable can be a rehearsal report, a reviewed document, a dataset, or another artifact appropriate to the project.

## Coordinate multiple agents

1. The owner creates the project and version. Give each author session the project permissions it needs and give an independent reviewer the `verify` permission.
2. The planning agent records a specification that defines the expected result. It creates or links tasks with owners and success conditions. Everyone uses the same project and version.
3. Each working agent reads the current records and their versions before editing. It links observations to existing issues or opens an issue when it finds a new problem. A combined write with `ledger_batch` is useful when the records need to be saved together.
4. The owner uses the overview's action queue and version filters to find unassigned issues, blocked work, and completion reports awaiting verification.

## Report completion and verify independently

1. The working agent records what was produced and where the evidence can be inspected. It reports completion on the associated item.
2. The reviewer reads the item, deliverables, and relevant history. It applies the independence rules for the item before calling `ledger_review` with an `evidence_uri`. Use the [ledger concepts](concepts.md#concepts) for the exact eligibility rules.
3. If the evidence is incomplete, record the reason and route the follow-up to the current owner. The reviewer reopens a verified item before it can be edited. The original verdict stays in history.

## Make a release decision

1. Group the release criteria under a milestone. Link each criterion to the tasks, specifications, and issues that explain how it will be assessed.
2. Record completion evidence for the selected version. Have an independent reviewer assess the criteria and record `MET` or `NOT_MET` with the supporting evidence.
3. Review the version overview and the release criteria together. Compare each verdict with unresolved issues and blocked work, then record the Go/No-Go reasoning in the associated ledger records. Archive the milestone if the plan is canceled.

For installation and session setup, see [agent integration](client-hooks.md). For tool-specific limits and inputs, see the [MCP reference](mcp.md).
