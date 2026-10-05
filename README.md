# NordQueueNotice 1.1.0

> Release build and installation requirements: see [BUILDING.md](BUILDING.md).
> Older local paths below describe historical test fixtures, not the release build.

The default notice is a generic waiting message, without advertising or a server
domain. After the first proxy start, customize `message` in the installed plugin's
`config.properties`; MiniMessage supports your own links. Existing configuration
is never replaced by this default.

Separate Velocity plugin for the clickable priority-queue website notice in
NanoLimbo. Uses current NordQueue 1.1.2 membership and current player/connection
identity. Only queued sessions on the queue backend are eligible. It does not
clear chat when players move to the main server; the former advertisement can
remain in their chat history.

Commands, all requiring `nordqueuenotice.admin` in metadata AND executor:

- `/nordqueuenotice` or `/nordqueuenotice reload`: queue one asynchronous reload.
- `/nordqueuenotice health`: inspect readiness, tracked sessions and pending reload.

The separate NordCommandsVelocity policy still applies to player commands:
its broad bypass does not grant this plugin's admin permission.

One 250 ms ticker replaces per-player delayed tasks. It inspects at most 256
tracked entries and attempts at most 32 frames per invocation, with a monotonic
token bucket allowing 128 attempts/sec sustained and a 32-frame burst. Concurrent
ticker callbacks are skipped, not queued behind one another. Capacity is 4096
session records; overflow only omits the optional advertisement, never changes
queue admission. Round-robin processing defers work fairly when the send budget
is exhausted. The initial delay and repeat interval are lower bounds; high
occupancy or scheduling delays can postpone delivery.

Messages are parsed once per valid configuration and cached as one immutable
frame containing the configured clear lines plus clickable notice. No file I/O,
MiniMessage parsing or whole-proxy player scan occurs in the send loop.
Disconnect removes only the matching owner; reconnection and new queue-connection
identities invalidate old deadline tokens. Shutdown cancels the ticker, interrupts
the single config reader and discards late results.

Legacy config.properties decoding is retained: ISO-8859-1 + Unicode escapes.
Existing files are never rewritten on startup or reload. Missing files are created
only during initialization. Invalid initial configuration disables notices; invalid
reload keeps the last complete settings. Repair the file and run an authorized
reload. Reader capacity is one in-flight request/result; repeated reload requests
are rejected while pending and do not create additional tasks/readers.

Supported fields and validation:

- interval-seconds: 3..3600; default 8.
- initial-delay-millis: 0..60000; default 750.
- clear-lines: 0..200; default 40; 0 disables blank-line clearing in limbo.
- message: nonblank MiniMessage, at most 8192 UTF-16 code units and 128 tag starts.

Files are at most 64 KiB; duplicate/unknown keys, malformed fields and bad Unicode
are rejected. MiniMessage uses strict tag closure. Rendered component bounds are
4096 nodes / depth 32 for traversed text, children, translation arguments,
selector/NBT separators and text/entity-name hovers; JSON representation <=64 KiB.
Templates are trusted administrator input, not a sandbox for arbitrary component
features. Current production settings passed a read-only compatibility check.

Build/checks: build.ps1, using isolated LOCAL Velocity libraries and the pinned
NordQueue 1.1.2 release; main/test classes are separated. Network scenarios live
in test-support/integration.cjs, fixture-only probes must never be deployed.
Source/release/evidence stay on this network share. See SECURITY-1.1.0.md.
1000-player production capacity and ordinary-client UI behavior are not claimed.
