# jeasync

A Forge **1.20.1** add-on (formerly "JEI Async") for
[JustEnoughItems](https://www.curseforge.com/minecraft/mc-mods/jei): it speeds up JEI's startup by
running plugin registration off the main thread, and it cuts the recipe memory that JEI's own
builders grow.

This is **not** a fork of JEI. It depends on a published JEI release as an ordinary dependency and
patches it at runtime with mixins.

- **Mod id:** `jeasync` (display name `jeasync`)
- **Targets:** Minecraft 1.20.1, Forge 47.x, JEI `15.74.0.228`
- **Java:** 17

## Performance, measured

**Every plugin is dispatched off the main thread.** There is no opt-in step. A plugin that throws
is demoted and re-run on the client thread.
The stock-modpack case is the fast case.

**The table below is a historical measurement**, taken against JEI 15.71 when the off switches
still existed. Those switches are gone: every plugin is dispatched, vanilla included. Client dev
environment, one local server, same world, same machine, against the `benchmarkJar` whose 65
synthetic plugins include 30 that opted in and 30 that declared themselves main-thread-only. Six
runs per arm, alternating between arms so that drift in the machine's load hits every arm equally:

| Configuration | `Starting JEI took` median | Range | `Registering recipes` median |
| --- | --- | --- | --- |
| `-Pjeasync.asyncLoading=false` - upstream behaviour, all plugins inline | **3.057 s** | 2.909 - 3.369 s | 1.897 s |
| `-Pjeasync.asyncVanilla=false` - every plugin eligible, `VanillaPlugin` inline | **2.476 s** | 2.387 - 2.980 s | 1.305 s |
| default - every plugin eligible, `VanillaPlugin` off-thread too | **2.309 s** | 1.808 - 2.534 s | 837 ms |

So the default is **-0.748 s, or -24%**, against upstream behaviour, and **-0.167 s, or -7%** against
the same add-on with vanilla left inline. The ingredient count was 5527 in every one of the
eighteen runs, so nothing was lost in any of them.

**The recipes phase is the signal to read, not the total.** It is 1.897 s upstream, 1.305 s with
vanilla left inline (-31%) and 837 ms by default (-56%). The totals move less because the rest of
startup is not parallelisable - see the phase breakdown below. The spread on the totals is also
wider than the spread on the phase: a single run of this benchmark varies by more than a second on
an otherwise idle machine, which is why the numbers are medians of six rather than single runs.

An earlier version of this README quoted **-29%**. That figure has been withdrawn: it was taken
while the mixins were not being applied at all, so both sides of the comparison were the same
unpatched JEI. The numbers above were produced after the mixin config was confirmed loaded via the
`MixinConfigs` manifest attribute and `PluginCallerMixin` was observed running.

Caveats worth weighing before trusting this: a synthetic plugin set whose work is a flat 20 ms
sleep each, and a dev build running against a local server. The honest reading is that the
mechanism works and moves the needle roughly in proportion to the fraction of plugins eligible, not
that -24% is a reliable figure for any given pack. A run is only clean once the client reaches
completion without crashing.

One thing worth knowing about the mechanism regardless of the numbers: the fallback is failure,
not declaration. A plugin that is fine off-thread costs nothing; a plugin that is not gets caught
and re-run, which costs its own duration twice for that phase. That is the price of not requiring
every author in the pack to opt in, and it is why each phase logs how many plugins ran inline.

### Where the remaining time goes

The same eighteen runs, broken down. These are medians of six:

| Phase | upstream | vanilla inline | default |
| --- | --- | --- | --- |
| `Registering recipes` | 1.897 s | 1.305 s | 837 ms |
| `Registering Runtime` | 771 ms | 774 ms | 724 ms |
| of which: building the search index | 423 ms | 419 ms | 507 ms |
| of which: building the ingredient list | 122 ms | 132 ms | 138 ms |
| `Registering categories` | 72 ms | 71 ms | 54 ms |

Two of those rows are the whole story and neither of them is parallelisable:

**The recipes phase is a floor, not a schedule.** Upstream, it is 1.897 s, of which `VanillaPlugin`
accounts for about 1.0 s and the 30 plugins that declared themselves main-thread-only for 600 ms
more. Those 1.6 s are serialised by contract, not by accident: `VanillaPlugin` is a stated
upstream prerequisite, and the other 30 asked for the main thread. Only the remainder - a few
hundred milliseconds across all phases - is off-thread work that could be overlapped at all. That
ceiling is why dispatching the eligible plugins earlier could not be shown to help.

**The search index build does not scale with threads.** See the next section.

Reproduce with `./gradlew runServer` in one terminal and
`./gradlew runClient -PquickPlayServer=127.0.0.1:25565` in another; JEI logs per-phase timings and
this add-on logs the inline/async split per phase. `tools/benchmark.ps1` drives the whole thing -
server, then the arms in rotation, then the medians - so the numbers above can be re-taken rather
than taken on trust.


### Search-index construction, independent of the pool

Search-index construction (`IngredientFilter.rebuildItemFilter`) crosses every ingredient with every
prefix to compute display names, mod ids and searchable strings. That now runs on a worker rather
than the render thread, so it no longer freezes the client while it happens. **This removes a stall
rather than reducing total time** - the same CPU is still spent, just not during a frame.

The add-on logs the cost per prefix, because the distribution is uneven and the distribution
decides whether there is anything left to win. On the bundled benchmark (5527 ingredients, default
config, so four of the seven prefixes are active):

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
None of that is covered by JEI's API contract, so it is not stable across releases - 15.32 to 15.71
moved `mezz.jei.core` to `mezz.jei.common`, rewrote the recipe index behind a new
`IngredientUidIndex`, and replaced the two search-dirty booleans with an enum set. `mods.toml`
therefore pins `[15.74.0.227,15.75)` rather than tracking the latest JEI.

Two of the mixins have to be compiled *in JEI's own package*, because they name package-private
types: `IngredientUidIndex` is the backing store for both `IngredientToRecipesMap` and `RecipeMap`,
and a Mixin `@Shadow` field has to declare its exact type.

That route compiles, and then the game refuses to start. A class in `mezz.jei.library.recipes.collect`
inside this mod's jar makes the jar export a package the JEI jar also exports, and ModLauncher
rejects the launch before a single mixin is applied:

```
java.lang.module.ResolutionException: Modules jei and jeasync export
package mezz.jei.library.recipes.collect to module minecraft
```

So the mixins that need those types avoid naming them. `IngredientUidIndexMixin` names its target as
a string (`targets = "mezz.jei.library.recipes.collect.IngredientUidIndex"`) and reaches the row map
with a bytecode-level `@Redirect` on the constructor call rather than a `@Shadow` on the field. The
redirect handler signatures mention only fastutil and `java.util` types, so nothing package-private
crosses the boundary. The same trick is how `IngredientToRecipesMap.get` has its
`Collections.unmodifiableList` call redirected.

One redirect does depend on a synthetic method name. The map is built inside the lambda passed to
`computeIfAbsent`, so the allocation lives in `lambda$getOrCreateRow$2` rather than in
`getOrCreateRow`, and that is the only place the `NEW` to redirect exists. The compiler is free to
renumber such a lambda, which is why the target is pinned to one exact JEI build. If a future port
renames it, the redirect finds no target and the self-check names `IngredientUidIndex` as the culprit
- loud, not silent.


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
2. If the callback throws, the plugin is recorded as incompatible. A missing class, or a
   registration rejected because that id is already present, stays as the error from the attempt
   that threw. Any other failure is re-run once, on the client thread, after the workers finish.
   That is the thread JEI itself would have used; the startup worker is still off the render
   thread, so a plugin that needed it used to fail there a second time. Plugins demoted by an
   earlier phase already ran on the client thread in this phase, so they are not re-run again at
   the end.
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

**1. JEI's collections are not thread-safe.** They are plain `HashMap`, `ArrayList`, `IdentityHashMap`,
`LinkedHashMap` and fastutil maps in `final` fields. The mixins guard each of those accesses. A
`final` field's contents cannot be replaced by a mixin, so the approach is to guard rather than
substitute - for example `MultiMap` takes its backing map's monitor instead of being rebuilt on a
`ConcurrentHashMap`. Reads that hand a collection back to a caller copy under the lock rather than
returning upstream's `unmodifiable*` view, because a view shares its iterator with the live
collection and the caller iterates after the lock is gone.

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
filtering, since only one index build is ever useful at a time.

Since 15.71 there are two kinds of dirt, and they are handled differently. Switching the search
implementation dirties everything and is handled by the full async `rebuildItemFilter`. A config
change to one search index dirties only that index, and upstream handles it by calling
`rebuildSearchIndexes` for the affected subset - that one stays synchronous, because it is cheap, and
because making it async would mean a config change could not be observed until a later frame.
`updateDirtyState` walks the ingredient list at most once either way, sharing the rebuilt
`IListElementInfo` list between the two paths.

## Memory

Three builders were pinning most of the heap. Each fix is a mixin against the same pinned JEI
build as the rest of the add-on; compare against the JEI version, not the add-on version.

### The grindstone memory explosion

JEI 15.49.0.200 backported "Protect grindstone recipes from mod crashes". Before that commit, one
mod whose enchantment threw inside the grindstone scan aborted the whole scan, and a pack could
run for months without noticing how much JEI was not registering. After it, every
item x enchantment x level pair is protected and completes, so the full cross product is built in
every pack - several gigabytes on a large one. The latest JEI on 1.20.x behaves the same way; the
crash protection is the intended behaviour, there is nothing upstream to roll back.

jeasync removes the two axes that multiplied without adding information:

- **Level and item.** A disenchant recipe exists per (enchantment, item, level). The extra items
  and levels above the first repeat the same result, so the grindstone now always keeps one recipe
  per enchantment - JEI's own uid, its first level, the first item that takes it - and drops only
  those redundant item and level copies. JEI log-spamming the failed enchantment check ("Failed to
  check if enchantment {X} can be applied to item {Y}") still names the mod whose enchantment
  started all of this: that enchantment does not fit the items it is being tested against.
- **Stack contents.** The enchanted stacks of one enchantment level share a single
  `Enchantments` compound instead of one per stack, and the outputs are the plain shared stacks
  rather than fresh grindstone-menu results. The heap cost of the stacks is now shared where it
  can be and counted once where it cannot.
- **Enchanting and repairing.** The anvil book-enchantment scan is the same cross product one
  shelf over: every registered ingredient paired with every enchantment and level. `skipAnvilEnchantRecipes`
  (default `true`) skips it, and `skipAnvilRepairRecipes` controls the anvil's small fixed repair
  list; with both off the anvil category hides itself. The grindstone does the opposite of hiding:
  it always keeps its repair pairs, one per repairable item, beside the one disenchant recipe per
  enchantment, so it shows real recipes like an anvil that keeps its repairs rather than blanking
  out.
- **Mod plugins that write the same lists.** Iron's Spells' arcane anvil plugin runs the same
  machinery one mod over: spell merges one per spell level, imbue pairs, an upgrade × upgradeable
  cross product, and attune recipes, keyed to every registered item (120k-plus in a large pack).
  That list is replaced before it is built with one recipe of each type - scroll upgrade, imbue,
  item upgrade, and affinity attune - so the category shows its real recipe types without the cross
  product.
- **The generic lever.** Plugins that hand JEI synthetic recipe objects carry no vanilla
  serializer for `skippedRecipeSerializers` to match, so `skippedCategories` drops a whole
  registration call by recipe category uid instead
  (`truedarkness:cursed_anvil_recipe_type`, `embers:dawnstone_anvil`, and so on in this pack).
  The plugin still pays its own generation cost; the rows, focus links and catalysts are never
  built. Default empty.
- **The empty rows.** Some plugins register recipes that carry no ingredients in any role — every
  ingredient they handed to the layout was air or nothing, and air does not exist as an ingredient
  in JEI, so the row shows an empty picture no click can look up with.
  `filterEmptyRecipes` (default `true`) drops those at registration, once each category is named
  in the log with the first row it dropped. A recipe with an empty output role is kept when it
  still has an input or catalyst, because that role may hold fluids or a slot the category draws
  itself; recipes that declare no output slot at all (fueling, compost, ingredient info) keep
  theirs too.

### Recipes can be dropped at registration

`skippedRecipeSerializers` lists recipe serializers whose JEI recipes are dropped at registration:
no rows, no focus links, no ingredient suppliers. The match is on the registry name of the
underlying vanilla recipe's serializer, and only recipes that *are* vanilla `Recipe` objects can
match, so purely synthetic JEI recipes and add-on wrappers pass through untouched. Reading that
serializer is guarded: JEI's own `JeiShapedRecipe` resolves a deferred registry entry, and some mod
recipes compute theirs from their own state, so either can fail while registration is still running
off the render thread. A recipe that cannot answer its serializer registers unfiltered rather than
aborting the plugin that owns it.

The default for `dedupRecipeSerializers` is `tconstruct:tipped_tool_transform`. Tinkers' Construct's
tipped-tool table (`TippedToolTransformRecipe`) is a datapack recipe, and packs and add-ons write one
per tool layout they cover, so a modded pack carries dozens to hundreds of them, and every one
registers now that JEI protects the checks from failing.

### Recipes can be collapsed to one per effect

`dedupRecipeSerializers` keeps a serializer's recipes but registers only the first of each distinct
effect. The effect is read reflectively: whichever modifier-, enchantment- or affix-typed field the
recipe class declares. Tinkers' tipped-tool transforms collapse to one recipe per tipped modifier
by default; adding an Apothic or Apotheosis affix serializer id collapses its affix recipes to one
per affix the same way. A recipe whose effect field cannot be read stays distinct, and only vanilla
`Recipe` objects are matched — synthetic add-on recipes pass through untouched. A category left with no visible recipes hides
itself, so nothing dropped leaves an empty category behind.

### Focus links are deferred until a recipe is first queried

`deferFocusLinks = true` (default) keeps JEI from storing the per-slot ingredient lists at
registration. They are built on a recipe's first visibility query and cached then, so a recipe
that is never queried holds nothing. The grindstone dedup and the deferral hit the same heap:
one fewer list per recipe that never gets looked at.

### Search strings are written to the index in batches

JEI 15.74.0.225 switched ingredient search to an in-memory Lucene index, and the build holds every
search string until the index is opened - the strings and the index bytes are both live at the
peak. jeasync writes bounded batches of 1024 strings instead, with one writer kept open for the
build and the merges capped, so the strings are collected while the index grows rather than all at
once at the end.

## Configuration

`config/jeasync-client.toml`, written on first launch. Registration itself is not a setting.
Every plugin runs off the render thread. The file has the thread count and the memory levers, and
each comment in the file says what it does.

| Setting | Default | Meaning |
| --- | --- | --- |
| `threads` | `0` | Worker pool size. `0` uses the processor count, and at least 2 |
| `deferFocusLinks` | `true` | Build each recipe's focus links at its first visibility query instead of at registration. Unqueried recipes hold nothing |
| `skipAnvilEnchantRecipes` | `true` | Skip JEI's anvil book-enchantment recipes, the ingredient x enchantment x level cross product. See [The grindstone memory explosion](#the-grindstone-memory-explosion) |
| `skipAnvilRepairRecipes` | `true` | Skip the small anvil repair-recipe list. The anvil keeps it when this is off |
| `filterEmptyRecipes` | `true` | Drop recipes that register with no ingredients in any role |
| `skippedRecipeSerializers` | *(empty)* | Recipe serializer ids dropped from registration entirely. See [Recipes can be dropped at registration](#recipes-can-be-dropped-at-registration) |
| `dedupRecipeSerializers` | `tconstruct:tipped_tool_transform` | Recipe serializer ids kept but collapsed to one recipe per effect. See [Recipes can be collapsed to one per effect](#recipes-can-be-collapsed-to-one-per-effect) |
| `skippedCategories` | *(empty)* | JEI recipe category ids whose whole registration call is dropped, for synthetic mod-plugin recipes no serializer can name |

Client-side rather than common: these govern how this client builds its indexes. Nothing here
changes world state, so a dedicated server ignores the file.

### System property overrides

A `-Djeasync.*` property wins over the file:

| Property | Overrides |
| --- | --- |
| `-Djeasync.threads=N` | `threads` |
| `-Djeasync.deferFocusLinks=true/false` | `deferFocusLinks` |
| `-Djeasync.skipAnvilEnchantRecipes=true/false` | `skipAnvilEnchantRecipes` |
| `-Djeasync.skipAnvilRepairRecipes=true/false` | `skipAnvilRepairRecipes` |
| `-Djeasync.filterEmptyRecipes=true/false` | `filterEmptyRecipes` |
| `-Djeasync.skippedRecipeSerializers=id[,id...]` | `skippedRecipeSerializers` |
| `-Djeasync.dedupRecipeSerializers=id[,id...]` | `dedupRecipeSerializers` |
| `-Djeasync.skippedCategories=id[,id...]` | `skippedCategories` |

The effective values are logged at startup as `[async-diag] effective settings: ...`.

The pool is sized to the CPU count rather than cached, because plugin registration is CPU-bound: a
cached pool would create one thread per concurrently submitted plugin, which in a large pack means
hundreds of threads.

### Vanilla runs with everyone else

`VanillaPlugin` is dispatched like any other plugin. Upstream runs it first and treats its failures
as fatal, and the inline retry still does. In the measurement above that overlap takes the recipes
phase from 1.305 s with vanilla inline to 837 ms.

Other plugins therefore run while JEI is half-registered. A plugin that depends on vanilla state is
supposed to throw, and the fallback re-runs it inline and then keeps it there. A plugin that reads
half-built state without throwing is not caught. The bundled benchmark cannot test that case,
because its plugins are synthetic sleepers.


## Building

```
./gradlew build
```

`./gradlew runClient` and `./gradlew runServer` launch a dev environment. JEI is declared with
ForgeGradle's `fg.deobf`, because the published JEI jar is compiled against SRG while dev runs use
Mojang mappings.

### The benchmark plugins never ship

The synthetic plugins used to measure startup (`loadtest.*`, 60 of them) live in the main source
set, because that is where Forge's annotation processor has to see them for the annotation scan
data to be generated. The shipping jar excludes them.

That exclusion is verified rather than trusted, because the failure mode is quiet. JEI finds
plugins through Forge's annotation scan data rather than by looking for classes on the classpath, so
a leftover scan-data entry naming a class that is no longer in the jar would make JEI try to load
it - a startup error at best, and at worst a plugin that half-registers. `verifyNoBenchmarkPlugins`
therefore opens the built jar and scans every class file for the benchmark package name, which
appears in the constant pool of anything that mentions it. Any hit fails the build, and the task
runs as part of `check`.

The check has been tested against a deliberately broken jar - removing the exclusion and rebuilding
makes it fail with the offending entries named - so it is known to be able to fail.

The dev runs use a separate `benchmarkJar` artifact, which keeps the synthetic plugins so there is
something to measure. It is never published; only `jar` is.


## Mixin self-check

On startup the mod force-loads every class it has a mixin for and reports the result:

```
Mixin self-check: 34 target classes loaded
```

On a dedicated server the client-only targets are skipped and counted separately, since those
classes cannot resolve without `net.minecraft.client`. A failure names the offending target, which
is considerably easier to diagnose than a later crash during gameplay.

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
  startup smoke test. With the opt-in default gone, that surface is now the whole mod list rather
  than the plugins that asked for it.
- **Search-index construction is moved off the render thread, not made faster.** Parallelising the
  index build itself was tried and does not pay: the per-element work is allocation-bound rather
  than CPU-bound, so it does not scale with threads. The per-prefix timings above say where the
  time actually goes.

- **Filtering has a brief lag.** The previous result is served until a newer one is published, so
  the list trails the search box slightly. That is inherent to doing the work off the render thread.
- `MultiMap.entrySet`/`keySet` and `IngredientSet.iterator` return snapshots rather than live views.
  Nothing in JEI uses the former; the latter loses fail-fast detection. Both are deliberate.
- The fork's async *startup* rework - deferring runtime publication, and waiting for the world to
  finish loading before starting - is not ported. It replaces JEI's runtime objects rather than
  patching them, so it cannot be delivered as mixins.