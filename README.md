# Better Pillager Campaigns

## Scope and authority

This repository owns its mod-specific behavior and authoring inputs. Read [local instructions](AGENTS.md)
and the [shared documentation/policy index](../../better-content-modpack/docs/README.md).


Better Pillager Campaigns is a server-side Minecraft Forge mod for Minecraft 1.20.1. It supplies frequent overland scout pressure and sparse, warned pillager assaults for Survival players.

Each player has independent eligible-time clocks: scouts arrive every 6–12 minutes with 3–6 members and withdraw after two active minutes; assaults arrive every 60–120 minutes, warn two surface minutes before arrival, then deliver three progress-gated waves of 16–24 members for one player. Nearby players within 64 blocks share one scaled encounter, capped at 48 members per wave. Groups dispatch early from deterministic virtual origins 512–768 blocks away so those windows remain arrival windows, then advance immaterially at 1.6 blocks/second for scouts or 1 block/second for assaults. This strategic leg never loads or requires distant chunks.

## Development

Java 17 and the checked-in Gradle wrapper are required.

```sh
./gradlew verifyFast
./gradlew verifyFull
./gradlew verifyWorld
./gradlew runCampaignHarnessServer
./gradlew runCampaignHarnessClient
./gradlew invasionCoreExperiment
./gradlew -q :runner:run --args='example'
./gradlew -q :runner:run --args='mvp /path/to/invasion-runtime-spec.json build/invasion-readiness'
```

`verifyFast` includes the fixed 1,024-seed policy corpus. `verifyFull` adds headless Forge GameTests, and `verifyWorld` runs those tests with the exact It Takes a Pillage and Savage & Ravage runtime dependencies.

## Interactive campaign harness

`runCampaignHarnessServer` starts an isolated localhost server on port `25566` with only Forge, KotlinForForge, Better Pillager Campaigns, It Takes a Pillage, Savage & Ravage, and Blueprint. Its flat Survival world is retained under the ignored `run/campaign-harness-server/` directory. The first launch follows Minecraft's normal EULA acknowledgement flow; after accepting it there, rerun the task. Start `runCampaignHarnessClient` in another shell and connect to `127.0.0.1:25566`.

The server run enables permission-four harness commands that do not register in an ordinary launch. Enter them in the dedicated-server console without a leading slash:

```text
better_pillager_campaigns harness spawn scout immediate <player> [intensity]
better_pillager_campaigns harness spawn assault immediate <player> [intensity]
better_pillager_campaigns harness spawn scout routed <player> [intensity]
better_pillager_campaigns harness spawn assault routed <player> [intensity]
better_pillager_campaigns harness next_wave <player>
better_pillager_campaigns harness protect <player>
better_pillager_campaigns inspect <player>
better_pillager_campaigns reset <player>
```

Intensity is optional and bounded to `0..5`. `immediate` selects loaded terrain in the normal 48–72-block arrival band while bypassing strategic travel; all roster, packet, spawn, single-path, targeting, and cleanup behavior remains real. `routed` expedites the virtual strategic leg but leaves production local-approach selection and validation in place. `next_wave` is accepted only after the current assault wave has fully materialized. `protect` grants a test-only invulnerability ability while retaining Survival mode, so a routed campaign can be observed without combat ending the target's soak. Use `resetCampaignHarnessWorld` when an explicitly fresh harness world is wanted.

The `mvp` runner gate proves deterministic Core properties only: per-player cadence, bounded scaling, composition, effect lifecycle, and terminal resolution under authored surface observations. It does not simulate Minecraft combat, entity AI, terrain, or player behavior.

Export the exact available roster and scalar rules from a running instance with `/better_pillager_campaigns export_runtime_spec`. The file is written beneath the world at `better_pillager_campaigns/exports/invasion-runtime-spec.json`.

## Architecture

- `invasion-core/` owns both clocks, grouping, wave budgets, roster composition, deterministic strategic traversal, caps, lifecycle, persistence shapes, and the durable effect outbox.
- `runner/` executes the exact compiled Core against synthetic observations and writes the hard readiness report.
- `src/main/kotlin/com/bettercontent/pillagercampaigns/` records genuinely loaded Minecraft terrain, executes warning/materialize/retire effects, persists the Core snapshot and terrain atlas, and maintains native mob targeting.

The atlas stores a compact surface column for chunks that normal play has loaded. After virtual travel completes, local routing considers only the 48–72-block approach band. The graph requires a solid floor, two blocks of clearance, one-block ascent, two-block descent, and never traverses unknown cells. Forge reads live terrain only with `getChunkNow`; no campaign route loads, generates, or tickets a chunk. A known wall or cliff produces an exterior defensive frontier. Exact placement uses only connected exterior columns. One real lead mob validates the selected frontier at materialization; after that, native combat goals normally own navigation. A campaign-only fallback briefly borrows movement after 40 ticks of idle navigation or 80 ticks without one block of displacement, then releases movement ownership after at most 100 ticks with a 40-tick cooldown. A useful route survives that handoff unless combat/target availability requires stopping, avoiding periodic pursuit pauses. Movement in any direction counts as progress, so routing around a wall is not treated as failure. The fallback leaves item use and spell casting alone, preserves visible firing positions within 16 blocks for native ranged mobs, and suppresses takeover for 80 ticks after a shot or a hit against the current visible target. It tries the player position or a short reachable lateral waypoint along the near side of an obstruction, retaining a successful side across movement handoffs and rejecting partial shortcuts that would undo the detour, with at most one query per retry, four campaign hunting queries per level tick, staggered retries and failure backoff up to 100 ticks plus per-mob staggering. Queries use a search radius capped at 32 blocks independently of native target-acquisition range, require their complete native search region to be loaded, and shrink that radius near loaded boundaries. Lateral probes follow the actual adjacent obstruction rather than only the target bearing. Sealed defenses remain valid: hunters neither teleport nor break blocks. Campaign pillagers equipped with TConstruct crossbows use a dedicated goal because vanilla crossbow AI does not recognize that item. It shares bounded path recovery and requires five consecutive visible ticks before stopping to fire; losing sight resumes pursuit. Scout members retaliate against a recent attacker for 200 ticks. Failed local searches retry every 100 ticks and retire the encounter after 2,400 eligible surface ticks so cadence cannot remain occupied indefinitely.

World saves use campaign schema 4. Schema-2 eligible clocks, cadence, sequence, and outcome adjustment migrate while active schema-2 encounters are cleared. Active schema-3 encounters retain their pressure and roster state but restart at the virtual-travel boundary because their atlas paths used the retired distant-terrain contract.

## Commands

- `/better_pillager_campaigns status [player]` reports cadence, encounter phase, virtual progress, and the last local-route result
- `/better_pillager_campaigns force [scout|assault] [player]` succeeds only when a real encounter materializes on a loaded, validated local approach; rejection leaves pressure unchanged
- `/better_pillager_campaigns inspect [player]` reports the live encounter, wave, targeting, provenance, virtual journey, local-route diagnostics, and roster state
- `/better_pillager_campaigns reset [player]`
- `/better_pillager_campaigns export_runtime_spec`

## Community

For modpack discussion and playtest feedback, join the [Better Content Discord](https://discord.gg/EkRnZbzqS9).

### Injury integration
Death’s Door players remain eligible campaign targets. Only confirmed final death triggers death retirement; low-health spacing uses semantic HP, including zero at Death’s Door.
