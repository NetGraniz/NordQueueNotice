# NordQueueNotice 1.1.1

Queue notices for Velocity and NanoLimbo, using NordQueue membership and current player/connection identity.

Only queued sessions on the queue backend receive notices. Moving to the main server stops further sends but does not erase a notice already in chat history.

## Configuration

The default is a generic waiting message with no advertising or server domain. After first startup, customize `message` in the installed `config.properties`. MiniMessage supports clickable links, including a priority-queue website.

Existing configuration is never replaced by the default. Properties retain ISO-8859-1 decoding with Unicode escapes. Startup creates a missing file; neither startup nor reload rewrites an existing one.

| Field | Range | Default |
| --- | --- | --- |
| `interval-seconds` | 3..3600 | 8 |
| `initial-delay-millis` | 0..60000 | 750 |
| `clear-lines` | 0..200; 0 disables blank-line clearing in limbo | 40 |
| `message` | Nonblank MiniMessage; at most 8192 UTF-16 code units and 128 tag starts | Generic waiting message |

Files are limited to 64 KiB. Duplicate or unknown keys, malformed fields and bad Unicode are rejected. MiniMessage requires strict tag closure.

Rendered components are limited to 4096 traversed nodes, depth 32 and a JSON representation of at most 64 KiB. Traversal covers text, children, translation arguments, selector/NBT separators and text/entity-name hovers.

Templates are trusted administrator input, not a sandbox for arbitrary component features. Earlier production settings passed a read-only compatibility check; review your installed file when updating.

## Commands

- `/nordqueuenotice` or `/nordqueuenotice reload` queues one asynchronous reload.
- `/nordqueuenotice health` reports readiness, tracked sessions and pending reload.

## Permissions

| Permission | Allows |
| --- | --- |
| `nordqueuenotice.admin` | All commands above |

Both command metadata and the executor check this node. Player grants come from Velocity's permission provider; no default player grant is registered. Backend-only permissions do not apply here.

NordCommandsVelocity's command policy is separate. Its bypass permission does not grant `nordqueuenotice.admin`.

## Scheduling and limits

One 250 ms ticker handles notices. Each invocation inspects at most 256 tracked entries and attempts at most 32 frames. A monotonic token bucket permits 128 attempts per second sustained, with a 32-frame burst.

Concurrent ticker callbacks are skipped rather than queued. At most 4096 session records are tracked; overflow omits the optional notice without changing queue admission.

Round-robin processing defers work when the send budget is exhausted. Initial delay and repeat interval are lower bounds: occupancy and scheduling delays can postpone a send.

A valid configuration is parsed once into one immutable frame containing blank lines and the clickable notice. The send loop performs no file I/O, MiniMessage parsing or whole-proxy player scan.

Disconnect removes only the matching owner. Reconnection or a new queue-connection identity invalidates old deadline tokens. Shutdown cancels the ticker, interrupts the single config reader and discards late results.

## Reload failures

Invalid initial configuration disables notices. An invalid reload retains the last complete settings. Repair the file and run an authorized reload.

The reader permits one in-flight request/result. Repeated requests while busy are rejected without creating extra tasks or readers.

## Build and tests

Use Maven 3.9+ and JDK 25: `mvn clean verify` or `./build.ps1`. The current release uses the pinned NordQueue 1.1.3 dependency; main and test classes are separate. See [BUILDING.md](BUILDING.md).

The 1.1.0 security checks used NordQueue 1.1.2. Their historical report, `SECURITY-1.1.0.md`, is excluded from the public repository. Historical local-library and network-share workflows are not the current release build.

Network scenarios are in `test-support/integration.cjs`. Probes are fixture-only; never deploy them to production.

These checks do not establish 1000-player production capacity or validate every ordinary-client UI behavior.
