# Better Pillager Campaigns Testing

`./gradlew verifyFast --no-daemon` runs the fixed 1,024-seed Core corpus, runner, Forge-facing unit tests, and the Core 90% line-coverage gate. `./gradlew verifyFull --no-daemon` adds headless Forge GameTests. `./gradlew verifyWorld --no-daemon` runs the Forge lane with the exact authored roster mods.

## Isolated source GameTest world

Each Gradle invocation chooses a fresh nonshipping `build/gametest/<uuid>` directory in the actual ForgeGradle GameTest RunConfig. Only that native fixture receives the tracked `gametest/server.properties` and structure copies: flat plains, fixture seed 0, bedrock/dirt/grass layers, no lakes, features or structures. Ordinary developer `run`, visual/data/operator inputs, production pack worlds, `runServer` and `runClient` are untouched. The compatibility `cleanGameTestWorld` task now requires a fresh fixture and deletes nothing; no previous world or settings are reset, relocated or merged. The native fixtures assert the actual flat generator, seed, minimum Y and dry authored body, rather than trusting properties alone. Both wide-arena templates declare their full 34×8×34 bounds. `verifyFast` also checks directory isolation, absent-only settings preparation/preservation and synthetic severity rejection; actual latest/debug ERROR/FATAL fails the native gate. Retain complete native fixture/build/log trees under the shared retention policy across handoffs.

Retaliation uses three separated native lanes with the same primary and three original scout archetypes, each with its own same-species NoAI/invulnerable zombie. Each scout receives a real native Hurt event from its own zombie; all seven original actors must remain alive, UUID-identical and natively ticking at the exact per-scout latest-hit 190/200 checks. Scouts gain no health/invulnerability and no goals, arrows or damage events are suppressed; the total 340-tick deadline is unchanged. The lanes isolate observed allied-arrow exposure. Historical fatal callbacks were not captured: the observed arrow chain supports an inference, not an invented death event; retained environmental-fluid evidence likewise does not identify every historical killer.

## Deterministic Core gate

```sh
./gradlew -q :runner:run --args='example' > build/invasion-runtime-spec.json
./gradlew -q :runner:run --args='mvp build/invasion-runtime-spec.json build/invasion-readiness'
```

The runner proves scheduling, fixed-point immaterial travel, arrival timing, per-player fairness, scaling bounds, roster constraints, durable effects, and terminal lifecycle. Strategic route tests cover open terrain, sealed walls, cliffs, and unknown cells. It is not a Minecraft combat or AI simulation.

For the installed pack roster, run `/better_pillager_campaigns export_runtime_spec` after registries load and pass the exported `better_pillager_campaigns/exports/invasion-runtime-spec.json` to the runner.

## Required live validation

- Use Survival in the Overworld with view and simulation distance four. Establish 30 minutes of living online presence locally before expecting fresh campaigns or using force/harness spawning.
- Verify new players and new dimensions have no fabricated regional history, 30 accumulated minutes establish the core ~128-block area, influence fades to ~256 blocks, and saving/reopening retains earned presence without adding offline time. Creative, Spectator and dead ticks must not earn presence.
- Travel to unfamiliar ground during approach and between packets/waves: fresh interception/spawning waits, including participant and fallback choices, while already spawned mobs may pursue normally. Return to familiar ground and verify progress resumes without resetting global eligible-time clocks.
- Verify silent scouts arrive within 7,200–14,400 eligible ticks and contain 3–6 ranged/line recruits.
- Hit a retreating scout and verify it resumes attacking for ten seconds, then returns to its ordinary campaign target.
- Verify TConstruct-equipped campaign pillagers pursue reachable players and fire arrows.
- Verify assaults arrive within 72,000–144,000 eligible ticks and receive the full 2,400 surface-tick warning.
- Verify each assault has three 16–24-member waves and advances after half the prior wave falls or after 1,500 ticks.
- Go underground before delivery; verify both clocks continue for an eligible Overworld Survival player but delivery waits for viable surface terrain.
- Explore at least 768 blocks around a base so its atlas is known, build a sealed two-block wall, and verify the squad appears outside and attempts native navigation with bounded hunting recovery, without breaking blocks.
- Verify a route stalls at never-generated or never-recorded terrain, and that querying the atlas neither loads nor generates a remote chunk.
- Travel away from a bed or base and verify pressure follows the current player rather than attacking the empty home.
- Clear successive invasions and verify intensity rises within `0..5`; die to one and verify intensity pressure falls and the death grace applies.
- Verify support and elite recruits remain capped at one per wave, a final-wave elite appears at intensity 3+, and ravagers appear only at intensity 5.
- Verify no tick exceeds 8 spawns, no rolling second exceeds 24, and tracked campaign population never exceeds 96.
- Ignore or leave an invasion and verify remaining tagged entities retire within the configured idle/unavailable limits.
- Load a schema-2 world copy and verify clocks/outcome pressure survive while an active legacy encounter is cleared with one explicit warning.

The hunting matrix reuses one arena and one player/mob pair sequentially for thirteen cases (450 native ticks each; 650 for the two-wall moving-target workload). Before each case clock, fixture-only public chunk warming establishes the owned FULL 32+8 collision halo and native actor/online-target readiness must complete within 20 ticks. Halo validity is checked during the case; no production force-load policy changes. The original total 6500-tick deadline remains (6050 case ticks plus at most 260 readiness ticks). This avoids simultaneous structure preparation and cross-case combat/ticket interference. The original eleven scenarios are run in separate single-arena batches, preserving their assertions and per-test deadlines while bounding synchronous batch setup.

The hunting cases exercise every authored recruit plus ravagers with native combat goals replaced by a deliberately non-progressing native-priority MOVE goal. This isolates movement starvation from legitimate stationary spells/attacks. Each must recover and navigate a wall opening or establish a visible ranged firing position using real entity movement. Additional cases cover TConstruct crossbow firing after rounding the wall, a moving target plus a new obstruction, a native archer retaining its firing position, idempotent goal installation, and an enclosed defense that must remain intact. Online invulnerable Survival targets supply real entity-ticking tickets; loaded structure chunks alone are not sufficient to prove AI movement. Pure progress tests cover idle/stalled deadlines, detour displacement, jitter, target changes and clock reset.

Forge GameTests create a real Survival dummy player at an explicit Y, exercise open and closed navigation gates with `Path.canReach()`, materialize a real pillager outside a sealed defense, reject solid/fluid occupation, prove atlas data survives an actual remote chunk unload without reloading it, and verify every authored EVENT spawn's targeting/provenance/cleanup. Direct materialization fixtures publish their authored active encounter into world data before invoking the adapter, matching production's transition-before-effect ordering, and assert that successful members are living, nonremoved and registered. The stale-join regression serializes a real campaign member to NBT, removes it as unloaded, verifies native loaded-from-disk registration accepts it while its encounter is active, then ends the encounter and verifies both saved and fresh stale members stay out of UUID/ID lookups, visible entity storage and collision sections. Native combat feel remains a live playtest requirement.

## Short interactive server check

Run `./gradlew runCampaignHarnessServer --no-daemon`, complete the standard first-run EULA acknowledgement if needed, and rerun it. In another shell run `./gradlew runCampaignHarnessClient --no-daemon`, join `127.0.0.1:25566`, and use the server console:

```text
better_pillager_campaigns harness spawn scout immediate <player> 5
better_pillager_campaigns harness protect <player>
better_pillager_campaigns inspect <player>
better_pillager_campaigns reset <player>
better_pillager_campaigns harness spawn assault immediate <player> 5
better_pillager_campaigns inspect <player>
better_pillager_campaigns harness next_wave <player>
better_pillager_campaigns harness next_wave <player>
better_pillager_campaigns reset <player>
```

This is a manual bounded exercise, not a soak or timing gate. The immediate form bypasses only distant atlas travel. Use the `routed` form after exploring recorded terrain when the production strategic path is the subject of the check. The harness loads the authored optional roster but no Better Content modpack, keeps its world between runs within the active task, and binds only to localhost. `./gradlew resetCampaignHarnessWorld` resets that generated world during diagnosis.
