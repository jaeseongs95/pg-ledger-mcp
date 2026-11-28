# User guide

[한국어](user-guide.ko.md) · [Documentation index](guide.md)

Use the admin console to organize project records and inspect their progress, evidence, and history.

## Projects and versions

1. In **Projects**, create a project with a stable ID, a record ID prefix, and a display name. Use **Edit** to change the display name and description.
2. Open **Project versions** from the project's shortcuts. Add the version you want to track, then open its workspace.
3. Use the version workspace to create or link specifications, issues, tasks, and release criteria. Unlinking an item removes its association with that version and keeps the item in the ledger.
4. Open **Overview** for the project and version. Use the action queue to find the records that need a decision or follow-up.

## Record work and evidence

| Record | What to capture |
|---|---|
| Specification | The expected outcome, rationale, and acceptance criteria. Link the work and issues that affect it. |
| Issue | The observed problem or deviation, its symptoms, and the cause when established. Add observations, evidence, and related work as they become available. |
| Task | A unit of work with an owner, purpose, and success condition. Record its deliverable and report completion. |
| Release criterion | A measurable Go/No-Go condition under a milestone. Link the items that support the decision and record the independent verdict. |

Open a record to inspect its related versions, linked items, evidence, and history. For a canceled milestone, use **Archive** on **Release criteria**. Restore it from the archived view when needed.

## Find records and trace changes

- Select a project and, where available, a version. Add the status, owner, or date filters that fit the question. The lists show both the number of matches and the number of rows on the current page.
- Click a date heading to change its sort order. Date ranges use the browser's time zone and the lists show local times.
- Use **History** to find changes by entity, author, operation, and date. Its version column follows the target's current version associations. Read the individual history entries to see when an association was added or removed.

For a complete example from planning to independent review, see the [practical workflows](workflows.md). For sign-in or permission management, see the [administrator guide](administration.md).
