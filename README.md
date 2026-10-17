# JEI Async

A Forge **1.20.1** add-on that speeds up [JustEnoughItems](https://www.curseforge.com/minecraft/mc-mods/jei)
startup by running plugin registration off the main thread.

This is **not** a fork of JEI. It depends on a published JEI release as an ordinary dependency and
patches it at runtime with mixins.

- **Mod id:** `jeiasync`
- **Targets:** Minecraft 1.20.1, Forge 47.x, JEI `15.32.0.173`
- **Java:** 17

## Performance, measured

**Every plugin is dispatched off the main thread.** There is no opt-in step. A plugin that cannot
cope with that is detected when it throws, demoted to the calling thread and re-run there, so the
stock-modpack case is now the fast case rather than the do-nothing case.

**The table below is from the previous opt-in-only behaviour and has not been re-measured since the
default changed.** It is kept as the measurement that established the mechanism works, and its
shape - roughly proportional to the fraction of plugins running concurrently - still holds. Client
dev environment, one local server, same world, same machine, against the benchmarkJar whose 65
synthetic plugins include 30 that opted in. One run per configuration:

| Configuration | `Starting JEI took` |
| --- | --- |
| `-Pjeiasync.asyncLoading=false` - upstream behaviour, all plugins inline | **2.773 s** |
| `-Pjeiasync.asyncLoading=true` - 30 of 65 plugins opted in | **2.302 s** |

That is **-0.471 s, or -17%**, with roughly half the plugins eligible. Both runs completed with zero
mixin failures and no exceptions. With the current default every plugin is eligible, so the upper
arm is the one to re-measure.

An earlier version of this README quoted **-29%**. That figure has been withdrawn: it was taken
while the mixins were not being applied at all, so both sides of the comparison were the same
unpatched JEI. The number above was produced after the mixin config was confirmed loaded via the
`MixinConfigs` manifest attribute and `PluginCallerMixin` was observed running.

Caveats worth weighing before trusting this: one run per arm, a synthetic plugin set whose work is
a flat 20 ms sleep each, and a dev build running against a local server. The honest reading is that
the mechanism works and moves the needle roughly in proportion to the fraction of plugins eligible,
not that -17% is a reliable figure for any given pack. A run is only clean once the client reaches
completion without crashing.

One thing worth knowing about the mechanism regardless of the numbers: the fallback is failure,
not declaration. A plugin that is fine off-thread costs nothing; a plugin that is not gets caught
and re-run, which costs its own duration twice for that phase. That is the price of not requiring
every author in the pack to opt in, and it is why each phase logs how many plugins ran inline.

Reproduce with `./gradlew runServer` in one terminal and
`./gradlew runClient -PquickPlayServer=127.0.0.1:25565` in another; JEI logs per-phase timings and
this add-on logs the inline/async split per phase.

### Search-index construction, independent of the pool

Search-index construction (`IngredientFilter.rebuildItemFilter`) crosses every ingredient with every
prefix to compute display names, mod ids and searchable strings. That now runs on a worker rather
than the render thread, so it no longer freezes the client while it happens. **This removes a stall
rather than reducing total time** - the same CPU is still spent, just not during a frame.

## Why the version is pinned exactly

The mixins overwrite internal methods (`RecipeManager.addRecipes`, `ErrorUtil.assertMainThread`,
`PluginCaller.callOnPlugins`, and a set of collection classes). None of those are covered by JEI's
API contract, so their names and signatures are not stable across releases. `mods.toml` therefore
pins `[15.32.0.173,15.33)` rather than tracking the latest JEI.

## How it works

JEI calls `PluginCaller.callOnPlugins` for each registration phase. This add-on replaces that loop
so that plugins register concurrently on a worker pool.

The call stays **synchronous from JEI's point of view**: it does not return until every plugin has
finished. That matters because JEI reads registration state immediately afterwards, and returning
early while workers were still writing would produce a half-built runtime that fails later and
further from the cause. The win is that plugins register alongside each other rather than one
after another.

### The fallback

Thread-safety is discovered by running the plugin and watching, not by asking it first:

1. Every plugin is dispatched to a worker.
2. If the callback throws **anything** - `Throwable`, not just `RuntimeException` - the plugin is
   recorded as incompatible, and the phase's failed plugins are re-run inline on the calling thread
   once all workers have finished.
3. An incompatible plugin is never dispatched off-thread again for the rest of the session, so it is
   demoted after its first failure rather than paying the double cost in every phase.

Plugins that are known ahead of time to need the main thread skip step 1 entirely:

- `VanillaPlugin`, because upstream treats it as a prerequisite for every other plugin and treats
  its failures as fatal. It stays inline, first, in list order.
- Anything implementing `IAsyncCompatiblePlugin` with `canExecuteAsync()` returning false.
- Everything else, when `-Djeiasync.requireOptIn=true` is set, which restores the previous
  opt-in-only behaviour.

The limit of this design is worth stating plainly: it catches plugins that fail *loudly*. A plugin
that corrupts shared JEI state without throwing produces no signal this add-on can act on, and the
corruption shows up later and further away. `requireOptIn` is the escape hatch for that.

### Opting out

```java
public class MyPlugin implements IAsyncCompatiblePlugin {
    @Override
    public ResourceLocation getPluginUid() { ... }

    @Override
    public boolean canExecuteAsync() { return false; }
}
```

A plugin author who *knows* their registration is main-thread-only - because it touches
`Minecraft.getInstance()`, level or block state, tile entity queries, player state, or GUI
construction - can say so and skip the discover-by-crashing cost. Implementing the interface
requires depending on `jeiasync`, since it is not part of JEI's API.

## Concurrency

Two things were needed to make off-thread registration safe.

**1. JEI's collections are not thread-safe.** They are plain `HashMap`, `ArrayList`, `IdentityHashMap`
and `LinkedHashMap` in `final` fields. The mixins guard each of those accesses. A `final` field's
contents cannot be replaced by a mixin, so the approach is to guard rather than substitute - for
example `MultiMap` takes its backing map's monitor instead of being rebuilt on a
`ConcurrentHashMap`.

**2. JEI asserted the main thread.** `RecipeManager.addRecipes` and
`IngredientManager.addIngredientsAtRuntime` call `ErrorUtil.assertMainThread()`. Since
`addRecipes` is what essentially every plugin calls during registration, leaving that assertion in
place makes off-thread registration impossible - the first recipe throws. The assertion is dropped
in those two methods only; everything underneath is already concurrent-safe, so nothing needs
bouncing to the main thread and there is no deferred work that could land after JEI indexes it.

`hideRecipes`, `unhideRecipes` and `hideRecipeCategory` deliberately keep the assertion: they are
user-driven GUI actions, and keeping it preserves upstream's diagnostics if another mod calls them
from the wrong thread.

## Async ingredient filtering

`IngredientFilter.getElements` sorts and visibility-checks the entire ingredient list, which is too
much to do while a frame is being drawn. That work is dispatched to a worker which first snapshots
everything it needs, then computes without holding the filter's monitor, so the render thread is
never blocked for the length of a search. The previous list keeps being returned until a newer one
lands, so typing in the search box does not blank the ingredient list.

Staleness is handled with a generation counter rather than cancellation - the running sort has no
cancellation points.

### Search index

`rebuildItemFilter` is dispatched to a worker as well, with its own dedupe state separate from query
filtering, since only one index build is ever useful at a time. `updateDirtyState` was collapsed
from two full ingredient walks to one, as `rebuildItemFilter` recomputes both the search index and
the sort index.

## Configuration

System properties, read once at startup. These are diagnostic escapes; a broken startup is not the
time to be reading a config parser stack trace.

| Property | Default | Meaning |
| --- | --- | --- |
| `-Djeiasync.asyncLoading=false` | `true` | Disable off-thread registration entirely |
| `-Djeiasync.requireOptIn=true` | `false` | Only dispatch plugins implementing `IAsyncCompatiblePlugin`; restores opt-in-only behaviour |
| `-Djeiasync.threads=N` | CPU count | Worker pool size |

The pool is sized to the CPU count rather than cached, because plugin registration is CPU-bound: a
cached pool would create one thread per concurrently submitted plugin, which in a large pack means
hundreds of threads.

## Building

```
./gradlew build
```

`./gradlew runClient` and `./gradlew runServer` launch a dev environment. JEI is declared with
ForgeGradle's `fg.deobf`, because the published JEI jar is compiled against SRG while dev runs use
Mojang mappings.
## Mixin self-check

On startup the mod force-loads every class it has a mixin for and reports the result:

```
Mixin self-check passed for 23 targets
```

On a dedicated server the client-only targets are skipped and counted separately, since those
classes cannot resolve without `net.minecraft.client`. A failure names the offending target, which
is considerably easier to diagnose than a later crash during gameplay.

## Known limitations

- **The safety net only catches loud failures.** A plugin that breaks shared state without throwing
  is not detected; see `requireOptIn`.
- **A plugin with a genuine bug in registration now runs twice.** If its callback throws for a
  reason that has nothing to do with threading, the inline retry throws again, so the phase logs two
  errors and costs that plugin's duration twice. Partial registration before the throw is also
  re-applied, which for an idempotency-sensitive callback - registering a category under an id that
  is already taken, say - can turn a logged error into a hard failure that upstream would not have
  produced. Sticky demotion bounds this to one phase.
- **Applying a mixin is not the same as it being correct.** The locks are derived from reading
  upstream source; a race that only manifests under genuine parallel load will not show up in a
  startup smoke test. With the opt-in default gone, that surface is now the whole mod list rather
  than the plugins that asked for it.
- **Search-index construction is moved off the render thread, not made faster.** Parallelising the
  index build itself would cut total time, but each prefix builds through its own
  `ISearchStorageBuilder` and there are only about six prefixes, so the ceiling is modest.
  It is not implemented.
- **Filtering has a brief lag.** The previous result is served until a newer one is published, so
  the list trails the search box slightly. That is inherent to doing the work off the render thread.
- `MultiMap.entrySet`/`keySet` and `IngredientSet.iterator` return snapshots rather than live views.
  Nothing in JEI uses the former; the latter loses fail-fast detection. Both are deliberate.
- The fork's async *startup* rework - deferring runtime publication, and waiting for the world to
  finish loading before starting - is not ported. It replaces JEI's runtime objects rather than
  patching them, so it cannot be delivered as mixins.