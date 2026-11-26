# jeasync

A **NeoForge 1.21.1** add-on (formerly "JEI Async") for
[JustEnoughItems](https://www.curseforge.com/minecraft/mc-mods/jei): it speeds up JEI's startup by
running plugin registration off the main thread, and it cuts the recipe memory that JEI's own
builders grow.

This is **not** a fork of JEI. It depends on a published JEI release as an ordinary dependency and
patches it at runtime with mixins.

- **Mod id:** `jeasync` (display name `jeasync`)
- **Targets:** Minecraft `1.21.1`, NeoForge `21.1.268`, JEI `19.76.1.481`
- **Optional compatibility targets:** JEI++ `1.1.0` (CurseForge file `9729247`), Recoleta `0.2.2`
  (CurseForge file `8444173`)
- **Java:** 21

## Why this branch is NeoForge, not Forge

JEI itself publishes both Forge and NeoForge builds for 1.21.1. JEI++ does not: every 1.21.1
JEI++ build is NeoForge-only. Since this add-on has to be installed next to JEI++ in the packs that
use both, the 1.21.1 line targets NeoForge.

## Port status

This branch is a port of the 1.20.1 / Forge 47 add-on, not a version-string bump. JEI 19.76 keeps
most of the internal class names the 15.75 mixins targeted, so the core architecture survives, but
several subsystems changed enough that the old patch could not be reused as-is:

- **Retained:** async plugin dispatch, off-thread startup, async ingredient filtering and search
  index builds, deferred focus links, the recipe skip/dedup filters, the grindstone disenchant
  collapse, the anvil scan switches, the moving-storage catalog release, XyCraft cache release,
  Recoleta eviction, stall detection, the load overlay and the mixin self-check.
- **Adapted for JEI 19:** the startup observer now starts on login plus recipe sync and has its own
  restart path; typed item wrappers store a `DataComponentPatch` instead of a copied NBT tag;
  `RecipeManager`'s recipe maps are immutable in 1.21; subtype lookups return nullable values; the
  legacy UID lookup was renamed; and `IngredientUidIndex` is package-private, so the fast path uses
  a bytecode redirect instead of naming that type. `Ingredient.getItems()` now keeps NeoForge's
  `customIngredient` path; ignoring it made every custom ingredient look empty.
- **Mekanism crash guard:** Mekanism's incomplete-recipe scanner treats `Ingredient.isSimple()` as
  "has vanilla values" and calls `getValues()`; simple custom ingredients such as
  `CompoundIngredient` throw there. An optional mixin makes that one lookup safe, so a genuinely
  empty custom ingredient is logged instead of crashing server startup.
- **Removed:** the 1.20.1-only per-mod patches. Avaritia, Apotheosis, Create, Iron's Spells,
  Mekanism, JEED, Tinkers with Enchants, Supplementaries, Capsule, Industrial Foregoing, Conduit,
  Sack Colouring and Almost Fluidified all changed their internals for 1.21.1, and carrying a
  best-effort mixin that targets the old shape would either do nothing or corrupt the new one.
  Those patches need to be re-authored against the current mod versions. JEI++ is handled natively
  by 1.1.0 and no longer needs a bridge. The anvil output rewriting and the NBT copy/intern
  micro-optimizations are also gone: JEI 19's typed item wrappers share component patches already.
- **Not re-benchmarked:** the measurements below were taken on 1.20.1 / JEI 15.71. The mechanism is
  the same, but the absolute numbers are historical.

## Performance, measured (historical, 1.20.1 / JEI 15.71)

**Every plugin phase at startup is dispatched off the main thread.** There is no opt-in step. A
plugin that throws is demoted and re-run on the client thread.
The stock-modpack case is the fast case.

The table below was taken against JEI 15.71 when the off switches still existed. Those switches are
gone: every plugin is dispatched, vanilla included. Client dev environment, one local server, same
world, same machine, with a synthetic plugin set. Six runs per arm, alternating between arms so that
drift in the machine's load hits every arm equally:

| Configuration | `Starting JEI took` median | Range | `Registering recipes` median |
| --- | --- | --- | --- |
| `-Pjeasync.asyncLoading=false` - upstream behaviour, all plugins inline | **3.057 s** | 2.909 - 3.369 s | 1.897 s |
| `-Pjeasync.asyncVanilla=false` - every plugin eligible, `VanillaPlugin` inline | **2.476 s** | 2.387 - 2.980 s | 1.305 s |
| default - every plugin eligible, `VanillaPlugin` off-thread too | **2.309 s** | 1.808 - 2.534 s | 837 ms |

So the default was **-0.748 s, or -24%**, against upstream behaviour, and **-0.167 s, or -7%**
against the same add-on with vanilla left inline. The ingredient count was 5527 in every one of the
eighteen runs, so nothing was lost in any of them.

**The recipes phase is the signal to read, not the total.** It was 1.897 s upstream, 1.305 s with
vanilla left inline (-31%) and 837 ms by default (-56%). The totals move less because the rest of
startup is not parallelisable - see the phase breakdown below. The spread on the totals is also
wider than the spread on the phase: a single run varies by more than a second on an otherwise idle
machine, which is why the numbers are medians of six rather than single runs.

Caveats worth weighing before trusting this: a synthetic plugin set whose work is a flat 20 ms
sleep each, and a dev build running against a local server. The honest reading is that the
mechanism works and moves the needle roughly in proportion to the fraction of plugins eligible, not
that -24% is a reliable figure for any given pack. A run is only clean once the client reaches
completion without crashing.

One thing worth knowing about the mechanism regardless of the numbers: the fallback is failure,
not declaration. A plugin that is fine off-thread costs nothing; a plugin that is not gets caught
and re-run, which costs its own duration twice for that phase. That is the price of not requiring
every author in the pack to opt in, and it is why each phase logs how many plugins ran inline.

### Where the remaining time goes (historical)

The same eighteen runs, broken down. These are medians of six:

| Phase | upstream | vanilla inline | default |
| --- | --- | --- | --- |
| `Registering recipes` | 1.897 s | 1.305 s | 837 ms |
| `Registering Runtime` | 771 ms | 774 ms | 724 ms |
| of which: building the search index | 423 ms | 419 ms | 507 ms |
| of which: building the ingredient list | 122 ms | 132 ms | 138 ms |
| `Registering categories` | 72 ms | 71 ms | 54 ms |

Two of those rows are the whole story and neither of them is parallelisable:

**The recipes phase is a floor, not a schedule.** Upstream, it was 1.897 s, of which
`VanillaPlugin` accounted for about 1.0 s and the 30 plugins that declared themselves
main-thread-only for 600 ms more. Those 1.6 s are serialised by contract, not by accident:
`VanillaPlugin` is a stated upstream prerequisite, and the other 30 asked for the main thread. Only
the remainder - a few hundred milliseconds across all phases - is off-thread work that could be
overlapped at all. That ceiling is why dispatching the eligible plugins earlier could not be shown
to help.

**The search index build does not scale with threads.** See the next section.

Reproduce with `./gradlew runServer` in one terminal and
`./gradlew runClient -PquickPlayServer=127.0.0.1:25565` in another; JEI logs per-phase timings and
this add-on logs the inline/async split per phase.

### Search-index construction, independent of the pool

Search-index construction (`IngredientFilter.rebuildItemFilter`) crosses every ingredient with every
prefix to compute display names, mod ids and searchable strings. That now runs on a worker rather
than the render thread, so it no longer freezes the client while it happens. **This removes a stall
rather than reducing total time** - the same CPU is still spent, just not during a frame.

The add-on logs the cost per prefix, because the distribution is uneven and the distribution
decides whether there is anything left to win. On a 5527-ingredient instance (default config, so
four of the seven prefixes are active):

| Prefix | Cost | Of which: computing strings | Of which: inserting |
| --- | --- | --- | --- |
| `unprefixed` | 55-68 ms | 1 ms | 2 ms |
| `mod_names` | 134-154 ms | 143 ms | 6 ms |
| `tooltips` | 100-140 ms | 119 ms | 2 ms |

Two things follow from that table.

**The cost is in computing the strings, not in inserting them** - so the insertion is not the place
to look, and the work is per-ingredient and independent, which is what makes parallelising it
tempting.

**Parallelising it does not work, and the reason is worth recording.** The per-element string
computation was spread over 10 to 16 threads and the result was `mod_names` going from 154 ms to
140 ms - against a run-to-run spread of more than a second. The work is allocation-bound rather
than CPU-bound: `mod_names` alone allocates a set, a regex matcher and a lowercased copy per
ingredient, and the resulting garbage collection does not scale the way the CPU work would. The
`tooltips` prefix is the larger cost and is left sequential regardless, because its strings come
from `IIngredientRenderer.getTooltip`, which is plugin code, and calling that from several threads
at once is not something this add-on is prepared to assume is safe.

## Why the version is pinned exactly

The mixins overwrite internal methods (`RecipeManager.addRecipes`, `ErrorUtil.assertMainThread`,
`PluginCaller.callOnPlugins`, and a set of collection classes) and read internal fields by name.
None of that is covered by JEI's API contract, so it is not stable across releases - 15.75 to 19.76
moved package-private classes, rewrote the startup observer, changed subtype lookup return types,
renamed the legacy UID accessors, and replaced typed item stack NBT with data components.
`neoforge.mods.toml` therefore pins exactly `[19.76.1.481]`: only the JEI release this build was verified against is
accepted, and older releases are not supported. Addon compatibility (JEI++ and Recoleta) is
separate and stays; only the JEI version range moves with each JEI update.

Two of the mixins have to interact with package-private JEI types: `IngredientUidIndex` is the
backing store for both `IngredientToRecipesMap` and `RecipeMap`, and a Mixin `@Shadow` field has to
declare its exact type. Naming that type from this jar would make the jar export a package the JEI
jar also exports, and ModLauncher rejects the launch before a single mixin is applied:

```
java.lang.module.ResolutionException: Modules jei and jeasync export
package mezz.jei.library.recipes.collect to module minecraft
```

So the mixins that need those types avoid naming them. `IngredientUidIndexMixin` names its target
as a string (`targets = "mezz.jei.library.recipes.collect.IngredientUidIndex"`) and reaches the row
map with a bytecode-level `@Redirect` on the constructor call rather than a `@Shadow` on the field.
The same trick is used for the `RecipeMap` fast path: `addRecipeIngredient` takes an
`IngredientUidIndex<Boolean>`, so instead of overwriting it, a redirect replaces the UID lookup
inside it. A future port that renames the redirect target finds no target and fails loudly.

## How it works

JEI calls `PluginCaller.callOnPlugins` for each registration phase. This add-on replaces that loop
so that plugins register concurrently on a worker pool.

The call stays **synchronous from JEI's point of view**: it does not return until every plugin has
finished. That matters because JEI reads registration state immediately afterwards, and returning
early while workers were still writing would produce a half-built runtime that fails later and
further from the cause. The win is that plugins register alongside each other rather than one
after another.

`Sending Runtime Unavailable` is the one phase that is not a registration. It is JEI's shutdown
callback, invoked on the render thread while the client is logging out, and its plugins unregister
NeoForge event listeners and drop client-side state. It runs sequentially on the client thread,
with upstream's ordering and error handling. Dispatching it to workers races the event bus and
leaves the plugin runtime half torn down at the exact moment the client reverts its registries.

The first phase, `Sending ConfigManager`, runs while NeoForge is still loading mods. The client
thread is blocked joining the mod-loading worker for that whole period, so the phase stays on the
calling thread. Dispatching it and waiting for the client thread to run a restore would deadlock
both threads. The add-on keeps a `modLoading` flag until common setup has completed, and every
phase before that point runs inline.

### Startup

`StartEventObserver.transitionState` is the single place startup and shutdown go through, including
the container-screen path that force-starts JEI when a screen opens early. The render thread marks
JEI started and returns; the worker runs `startRunnable`. A second start while that worker is busy
is recorded and applied when the current pass finishes, which is how JEI 19's own recipe-sync
restart is handled without waiting on the render thread. A client-tick fallback calls the same
`startIfReady` path if the login or recipe-sync event was missed.

A disconnect during startup is applied after the worker finishes, unless the player is already back
in a world. Tearing the runtime down on the render thread while a worker is still registering is
what leaves a half-built runtime behind.

### The fallback

Thread-safety is discovered by running the plugin and watching, not by asking it first:

1. Every plugin is dispatched to a worker.
2. If the callback throws, the plugin is recorded as incompatible. A missing class, or a
   registration rejected because that id is already present, stays as the error from the attempt
   that threw. Any other failure is re-run once, on the client thread, after the workers finish.
   That is the thread JEI itself would have used. Plugins demoted by an earlier phase already ran
   on the client thread in this phase, so they are not re-run again at the end.
3. An incompatible plugin runs on the client thread for later phases. This applies to
   `VanillaPlugin` too.

Vanilla is dispatched with everyone else. Upstream still treats a vanilla failure as fatal, and the
re-run preserves that. A plugin implements `IAsyncCompatiblePlugin` and returns false from
`canExecuteAsync()` to stay on the calling thread.

The limit of this design is worth stating plainly: it catches plugins that fail *loudly*. A plugin
that corrupts shared JEI state without throwing produces no signal this add-on can act on, and the
corruption shows up later and further away.

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
requires depending on `jeasync`, since it is not part of JEI's API.

## Concurrency

Two things were needed to make off-thread registration safe.

**1. JEI's collections are not thread-safe.** They are plain `HashMap`, `ArrayList`,
`IdentityHashMap`, `LinkedHashMap` and fastutil maps in `final` fields. The mixins guard each of
those accesses. A `final` field's contents cannot be replaced by a mixin, so the approach is to
guard rather than substitute - for example `MultiMap` takes its backing map's monitor instead of
being rebuilt on a `ConcurrentHashMap`. Reads that hand a collection back to a caller copy under the
lock rather than returning upstream's `unmodifiable*` view, because a view shares its iterator with
the live collection and the caller iterates after the lock is gone.

**2. JEI asserted the main thread.** `RecipeManager.addRecipes` calls
`ErrorUtil.assertMainThread()`. Since `addRecipes` is what essentially every plugin calls during
registration, leaving that assertion in place makes off-thread registration impossible - the first
recipe throws. The assertion is dropped on that path; everything underneath is made concurrent-safe
by the other mixins, so nothing needs bouncing to the main thread and there is no deferred work
that could land after JEI indexes it.

`hideRecipes`, `unhideRecipes` and the category hide methods deliberately keep their existing
locking: they are user-driven GUI actions and startup callbacks, and the hidden set is guarded
directly.

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
filtering, since only one index build is ever useful at a time.

Config changes that dirty only some search indexes are handled by JEI's `rebuildSearchIndexes` for
the affected subset. That path stays synchronous, because it is cheap and because making it async
would mean a config change could not be observed until a later frame. `updateDirtyState` walks the
ingredient list at most once either way.

Search strings are written to JEI's Lucene index in bounded batches of 1024, with one writer kept
open for the build, so the strings are collected while the index grows rather than all at once at
the end.

## Memory

### The grindstone disenchant scan

JEI 19 builds an enchanted copy of every item that supports each enchantment, for every level up to
the enchantment's maximum. The item and level copies add nothing to the viewer: the recipe uid
already identifies the recipe by enchantment and level, and a disenchant result is decided by the
enchantment alone. On a large pack that cross product is one of the largest retained recipe lists.

jeasync keeps the first real item per enchantment at level one and drops only those redundant
copies. Repair pairs are untouched and still come from JEI.

### The anvil scans

JEI's anvil category holds two scans: the book-enchantment scan, which is the ingredient x
enchantment x level cross product, and vanilla's small fixed repair-pair list. Both are switches:

- `skipAnvilEnchantRecipes` (default `true`) skips the book-enchantment scan.
- `skipAnvilRepairRecipes` (default `true`) skips the repair list.

With both on, the anvil category registers nothing and hides itself. Turning either off restores
JEI's own scan.

### Recipes can be dropped at registration

`skippedRecipeSerializers` lists recipe serializers whose JEI recipes are dropped at registration:
no rows, no focus links, no ingredient suppliers. The match is on the registry name of the
underlying vanilla recipe's serializer, and only recipes that *are* vanilla `Recipe` objects can
match, so purely synthetic JEI recipes and add-on wrappers pass through untouched. Reading that
serializer is guarded: a recipe that cannot answer its serializer registers unfiltered rather than
aborting the plugin that owns it.

### Recipes can be collapsed to one per effect or product

`dedupRecipeSerializers` keeps a serializer's or recipe type's recipes but registers only the first
of each distinct result. The result is read reflectively: whichever modifier-, enchantment- or
affix-typed field the recipe class declares, and a recipe that declares none is keyed on its
`getOutput` item or fluid, with the count and the fluid amount ignored. Tinkers' Construct's
tipped-tool transforms collapse to one recipe per tipped modifier, and its casting and melting
families to one recipe per part or molten metal; adding an Apothic or Apotheosis affix serializer id
collapses its affix recipes to one per affix the same way. A recipe whose result cannot be read
stays distinct, and only vanilla `Recipe` objects are matched.

The default is `tconstruct:tipped_tool_transform`, `tconstruct:casting_table`,
`tconstruct:casting_basin` and `tconstruct:melting`. Naming a recipe type covers every serializer
registered under it, which is how one entry catches all of Tinkers' casting variants.

### Recipes can be dropped by category

`skippedCategories` drops a whole registration call by JEI recipe category uid. That is the
category-level matching `skippedRecipeSerializers` cannot do: many plugins hand JEI synthetic
recipe objects that are not vanilla `Recipe`, so no serializer can be read. The plugin still pays
its own generation cost; the rows, focus links and catalysts are never built, and a category left
with nothing hides itself.

### Empty recipe rows

Some plugins register recipes that carry no ingredients in any role - every ingredient they handed
to the layout was air or nothing. `filterEmptyRecipes` (default `true`) drops those at registration,
once each category is named in the log with the first row it dropped. A recipe with an empty output
role is kept when it still has an input or catalyst, because that role may hold fluids or a slot the
category draws itself; recipes that declare no output slot at all (fueling, compost, ingredient
info) keep theirs too.

### A mod plugin's recipe-viewer catalog is released after load

Sophisticated Storage in Motion registers an ordinary JEI plugin and builds its display catalog
during that plugin's registration callbacks, keeping the finished object in a field on the plugin
instance. Nothing in the mod clears that field and nothing JEI does reaches it, so once load
finishes the whole catalog - every display, every ingredient stack inside it - is pinned by a
plugin object that is not consulted again until a player opens a moving-storage GUI. In the pack
this was measured in, that one field was 155 MB and 14.6 million objects, about 60% of the retained
heap.

jeasync records the plugins of each dispatch phase and, at the load boundary, nulls every field
whose declared type is that catalog interface, immediately before JEI is published. Matching is on
the declared type rather than the plugin class, so the same release covers the storage, backpacks
and in-motion plugins, and mods that are absent match nothing. The cost is the first
moving-storage query after load: the mod's own `getCatalog` rebuilds the catalog when the field is
null. `RecipeViewerCatalogCache` does both halves.

### Focus links are deferred until a recipe is first queried

`deferFocusLinks = true` (default) keeps JEI from storing the per-slot ingredient lists at
registration. They are built on a recipe's first visibility query and cached then, so a recipe that
is never queried holds nothing.

## Configuration

`config/jeasync-client.toml`, written on first launch. Registration itself is not a setting. Every
plugin registration phase runs off the render thread after mod loading; JEI's shutdown callback
runs on the client thread. The file has the thread count, stall detection and the memory levers,
and each comment in the file says what it does.

| Setting | Default | Meaning |
| --- | --- | --- |
| `threads` | `0` | Worker pool size. `0` uses the processor count, and at least 2 |
| `stallReportSeconds` | `120` | After this many seconds, a still-running plugin callback is logged with its worker thread's stack, a full thread dump and any JVM monitor deadlock, repeating at the same interval while it stays stuck. Diagnostics only: nothing is interrupted, failed or skipped. `0` disables the feature entirely; no watchdog thread is created and no callback tracking runs |
| `deferFocusLinks` | `true` | Build each recipe's focus links at its first visibility query instead of at registration. Unqueried recipes hold nothing |
| `skipAnvilEnchantRecipes` | `true` | Skip JEI's anvil book-enchantment recipes, the ingredient x enchantment x level cross product |
| `skipAnvilRepairRecipes` | `true` | Skip the small anvil repair-recipe list. The anvil keeps it when this is off |
| `filterEmptyRecipes` | `true` | Drop recipes that register with no ingredients in any role |
| `skippedRecipeSerializers` | *(empty)* | Recipe serializer ids dropped from registration entirely |
| `dedupRecipeSerializers` | `tconstruct:tipped_tool_transform`, `tconstruct:casting_table`, `tconstruct:casting_basin`, `tconstruct:melting` | Recipe serializer or recipe type ids kept but collapsed to one recipe per effect or product |
| `skippedCategories` | *(empty)* | JEI recipe category ids whose whole registration call is dropped |
| `optimizeXycraft` | `true` | Release XyCraft's intermediate rule-to-block-state map after load and collapse duplicate stacks in its derived item lists |

Client-side rather than common: these govern how this client builds its indexes. Nothing here
changes world state, so a dedicated server ignores the file.

### System property overrides

A `-Djeasync.*` property wins over the file:

| Property | Overrides |
| --- | --- |
| `-Djeasync.threads=N` | `threads` |
| `-Djeasync.stallReportSeconds=N` | `stallReportSeconds` |
| `-Djeasync.deferFocusLinks=true/false` | `deferFocusLinks` |
| `-Djeasync.skipAnvilEnchantRecipes=true/false` | `skipAnvilEnchantRecipes` |
| `-Djeasync.skipAnvilRepairRecipes=true/false` | `skipAnvilRepairRecipes` |
| `-Djeasync.filterEmptyRecipes=true/false` | `filterEmptyRecipes` |
| `-Djeasync.skippedRecipeSerializers=id[,id...]` | `skippedRecipeSerializers` |
| `-Djeasync.dedupRecipeSerializers=id[,id...]` | `dedupRecipeSerializers` |
| `-Djeasync.skippedCategories=id[,id...]` | `skippedCategories` |
| `-Djeasync.optimizeXycraft=true/false` | `optimizeXycraft` |

The effective values are logged at startup as `[async-diag] effective settings: ...`.

The pool is sized to the CPU count rather than cached, because plugin registration is CPU-bound: a
cached pool would create one thread per concurrently submitted plugin, which in a large pack means
hundreds of threads.

## Building

```
./gradlew build
```

`./gradlew runClient` and `./gradlew runServer` launch a dev environment. The build uses
ModDevGradle `2.0.148` with NeoForge `21.1.268`, so JEI is an ordinary NeoForge dependency - there
is no `fg.deobf` step, because NeoForge 1.21.1 runs on Mojang mappings in both dev and production.

JEI++ and Recoleta are `compileOnly` CurseMaven dependencies. They are optional at runtime; the
add-on does not link against either unless the mod is installed. JEI++ 1.1.0 handles its own JEI
integration, so there is no JEI++ bridge in this branch.

`runServer` needs an `eula=true` in `run/server/eula.txt` and a free port; the dev client can join
it with `./gradlew runClient -PquickPlayServer=127.0.0.1:25565`.

## Mixin self-check

On startup the mod force-loads every class it has a mixin for and reports the result:

```
Mixin self-check: 50 target classes loaded
```

On a dedicated server the client-only targets are skipped and counted separately, since those
classes cannot resolve without `net.minecraft.client`. A failure names the offending target, which
is considerably easier to diagnose than a later crash during gameplay. The mixin configs are
registered through the `[[mixins]]` entries in `META-INF/neoforge.mods.toml`; there is no refmap
because NeoForge uses official mappings at runtime.

## Known limitations

- **The safety net only catches loud failures.** A plugin that breaks shared state without throwing
  is not detected.
- **Vanilla runs alongside every other plugin.** A plugin that reads half-registered JEI state
  without throwing is not detectable, so the failure mode is missing recipes with no error logged.
- **A plugin that throws partway through has already published what it registered.** A duplicate
  id or a missing class is not run again in that phase, so the partial work stays as the first
  attempt left it. Later phases for that plugin run on the client thread. Any other failure is
  still run a second time, and a callback that is not idempotent can register twice.
- **Applying a mixin is not the same as it being correct.** The locks are derived from reading
  upstream source; a race that only manifests under genuine parallel load will not show up in a
  startup smoke test.
- **Search-index construction is moved off the render thread, not made faster.** Parallelising the
  index build itself was tried and does not pay: the per-element work is allocation-bound rather
  than CPU-bound, so it does not scale with threads.
- **Filtering has a brief lag.** The previous result is served until a newer one is published, so
  the list trails the search box slightly. That is inherent to doing the work off the render thread.
- **The 1.21.1 per-mod compatibility patches are not ported yet.** The generic recipe filters and
  the structural memory fixes are here; the patches that knew a specific mod's 1.20.1 internals are
  not.
- `MultiMap.entrySet`/`keySet` and `IngredientSet.iterator` return snapshots rather than live views.
  Nothing in JEI uses the former; the latter loses fail-fast detection. Both are deliberate.
- The fork's async *startup* rework - deferring runtime publication, and waiting for the world to
  finish loading before starting - is not ported. It replaces JEI's runtime objects rather than
  patching them, so it cannot be delivered as mixins.
