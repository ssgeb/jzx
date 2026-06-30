# Security and Reliability Hardening Design

## Goal

Harden the demo platform's authorization, assistant action confirmation, detection task dispatch, and Kafka worker delivery semantics without introducing a full enterprise IAM or outbox platform.

## Scope

This design addresses five verified issues:

1. Every authenticated user currently receives `ROLE_ADMIN`.
2. OSS detection previews can be requested anonymously.
3. Assistant confirmation is vulnerable to duplicate execution and unrecoverable intermediate state.
4. Detection upload confirmation can dispatch the same Kafka task more than once.
5. The Python worker can commit a consumed task without proving that the finished event was delivered.

The design does not introduce permission tables, a Kafka dead-letter topic, or a transactional outbox. Those are intentionally deferred because this project is a single-service demo.

## Authorization Model

### User roles

Add a non-null `role` column to `users` with supported values `ADMIN` and `OPERATOR`.

- Existing username `admin` is migrated to `ADMIN`.
- Other existing users are migrated to `OPERATOR`.
- New users default to `OPERATOR`.
- `UserDetailsServiceImpl` maps the stored role to exactly one Spring authority named `ROLE_<role>`.
- Unknown or blank roles are treated as `OPERATOR`, never as administrator.

### Detection task ownership

The existing task ownership rule remains the single source of truth:

- `ADMIN` may access all detection tasks.
- `OPERATOR` may access only tasks whose `created_by` matches the authenticated username.
- Read, retry, assignment, review, disposition, rework, trace, and result operations all resolve tasks through the ownership-enforcing service method.
- Anonymous and background Kafka processing are handled explicitly rather than receiving an implicit administrator authority.

### OSS previews

`/api/oss/preview` requires authentication. Before generating a signed URL, the controller delegates to a service that:

1. Rejects keys outside the `detection/` namespace.
2. Finds a detection task that references the requested original, preview, or result key.
3. Applies the same administrator-or-owner rule as task APIs.
4. Rejects unreferenced keys even when they use the allowed prefix.

This preserves browser image loading through the existing HttpOnly authentication cookie while preventing anonymous signing of arbitrary detection objects.

## Atomic Assistant Actions

Pending actions use the following state machine:

```text
PENDING -> EXECUTING -> COMPLETED
                    -> FAILED
PENDING -> CANCELLED
```

The service claims an action with one conditional database update equivalent to:

```sql
UPDATE chat_pending_action
SET status = 'EXECUTING', confirmed_at = CURRENT_TIMESTAMP
WHERE session_id = ? AND action_id = ? AND status = 'PENDING';
```

Only a request that updates one row may resume the graph. A concurrent request that updates zero rows reloads the action and reports its current terminal or in-progress state without executing it.

After graph execution, the action becomes `COMPLETED`. If graph resume throws, it becomes `FAILED` and stores a bounded error summary. Failed actions are not automatically retried because the system cannot always prove whether a downstream business mutation occurred. The user may create and confirm a new action after inspecting the failure.

Cancellation uses the same conditional-update rule from `PENDING` to `CANCELLED`.

## Idempotent Detection Dispatch

Upload completion has one internal implementation. The legacy `/mark-uploaded` endpoint remains temporarily for compatibility but delegates to the same service path as `/uploaded`.

The service validates uploaded object keys, then performs a conditional state transition. Only the request that changes an uploadable state to `UPLOADED` may dispatch Kafka work. Requests that observe `UPLOADED`, `QUEUED`, `DETECTING`, or a final state return the current task progress without dispatching again.

Each accepted dispatch creates a unique `dispatch_id` and stores it on `detection_task`. The created Kafka event carries this value. The worker copies it into the finished event. The backend accepts a finished event only when its `dispatch_id` matches the task's active dispatch.

The task also stores `last_finished_event_id`. Processing rules are:

- An event whose `dispatch_id` does not match the active dispatch is stale and ignored.
- An event whose `event_id` equals `last_finished_event_id` is a duplicate and ignored.
- A valid new event applies results and records its event ID in the same database transaction.
- Completion notifications are emitted only after a new event is successfully applied.

A retry generates a new `dispatch_id`, so delayed results from an earlier attempt cannot overwrite the retry.

## Kafka Worker Delivery

The worker publishes the finished event with a delivery callback and a finite configurable timeout.

1. Process the created event and upload result artifacts to OSS.
2. Produce the finished event with a delivery callback.
3. Call `flush(timeout)`.
4. Treat a non-zero queue length or callback error as delivery failure.
5. Commit the consumed created-event offset only after confirmed delivery.

On delivery failure, the worker exits without committing the offset. Its process supervisor can restart it, and Kafka will redeliver the uncommitted task. The backend dispatch/event guards make this retry safe. A DLQ is outside this demo's scope.

## Database Migration

Add a forward-only, idempotent migration that introduces:

- `users.role VARCHAR(16) NOT NULL DEFAULT 'OPERATOR'`
- `detection_task.dispatch_id VARCHAR(64) NULL`
- `detection_task.last_finished_event_id VARCHAR(128) NULL`
- `chat_pending_action.error_message VARCHAR(500) NULL`

Add an index on `detection_task.dispatch_id`. Existing `admin` is backfilled to `ADMIN`; all other blank or unsupported roles become `OPERATOR`.

The migration must use `information_schema` guards because this project executes SQL migrations against databases that may already contain portions of the schema.

## Error Handling

- Authorization failures return a business error without revealing whether another user's task or OSS key exists.
- Concurrent assistant confirmations return an already-processing/already-processed response and never execute the graph twice.
- A failed assistant action retains its failure state and bounded diagnostic message.
- Duplicate upload confirmations return current progress instead of reporting a new dispatch.
- Stale or duplicate finished events are logged at info level and acknowledged without mutating task results.
- Worker delivery failures are logged and terminate the worker with a non-zero exit code while leaving the source offset uncommitted.

## Testing

### Java

- Role mapping tests prove that `admin` and stored `ADMIN` receive `ROLE_ADMIN`, while ordinary or unknown roles receive only `ROLE_OPERATOR`.
- Task ownership tests prove that operators cannot read or mutate another user's task.
- OSS controller/service tests prove anonymous requests are rejected, owners are allowed, and foreign or unreferenced keys are rejected.
- Pending action tests prove conditional claiming, cancellation, duplicate confirmation rejection, completion, and failure state recording.
- Upload confirmation tests prove repeated and concurrent calls result in one dispatch.
- Finished event tests prove duplicate and stale dispatch events do not update results or send notifications.

### Python

- Producer success commits the consumed message exactly once.
- Delivery callback failure does not commit and raises a worker-fatal error.
- Flush timeout/non-zero remaining queue does not commit.
- Finished events preserve the incoming `dispatch_id` and use a unique event ID.

### Regression

Run the full Maven suite, all frontend contract tests and production build, and the complete non-browser Python unit suite. Verify login as both `ADMIN` and `OPERATOR`, task ownership, authenticated image preview, one successful assistant confirmation, and Kafka-unavailable failure behavior in the browser/API.

