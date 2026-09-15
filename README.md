
# ChunkOptimizer v1.0.1

**NeoForge 1.21.1 — MIT License**

A live chunk dispatch optimizer for Minecraft that replaces vanilla's stateless chunk
sender with a motion-vector spiral, adaptive cadence control, and an edge ticketer that
seeds world generation ahead of the player. Zero configuration required. Drop it in and
it works.

> NOTE: For servers and low-end rigs, use PERFORMANCE in the mod configurations. 
> Go to the main menu → click mods → look for ChunkOptimizer → toggle the config.
> For high-end rigs, use SMOOTH for quick adaptive gameplay (recommended: Ryzen 5700/RTX 3070.)

---

## What is ChunkOptimizer?

When you move through the world, Minecraft has to decide which chunks to send to your
client and in what order. Vanilla does this badly — it re-sorts its entire queue of
pending chunks from scratch every tick, dispatches them in hash-order chaos with no
memory of what it just sent, and issues batches that silently shrink when the nearby
generation hasn't finished yet. The result is a visible stutter even when your hardware
has spare headroom. The engine is fighting itself.

ChunkOptimizer replaces the dispatch layer entirely. Instead of re-sorting thousands of
chunk positions every tick, it chains each batch off the last dispatched position, biased
toward where you're heading. Dispatch cadence adapts as your render distance fills in.
An edge ticketer reaches past your view distance and seeds world generation before you
arrive. The chunk loader stops fighting generation and starts cooperating with it.

The result is a dispatch system that knows where you've been, where you're going, and
how fast the hardware underneath it is keeping up — and adjusts accordingly.

---

## The Unhinged Collector

Vanilla Minecraft's chunk sender contains a method called `collectChunksToSend`. Every
time it runs, it takes the full set of pending chunk positions — potentially thousands of
entries — and sorts them by distance from the player using a comparator. It picks the
nearest ones, sends them, and throws the sorted result away. Next tick, it does the
entire sort again from scratch, with no memory of what it just dispatched or where the
player is heading.

There is no spiral. There is no chain. The iteration order of the pending set is
hash-based, meaning the input to the sort is non-deterministic. You are sorting chaos
into order, discarding the result, then sorting chaos again.

The sort also silently shrinks. When a nearby chunk isn't ready yet — generation hasn't
finished — `getChunkToSend` returns null and that slot disappears from the batch without
replacement. So you can end up dispatching two chunks when you asked for eight, with no
mechanism to backfill. The server doesn't know. The client doesn't know. The stutter
just happens.

That is the unhinged collector. It is not a bug in the traditional sense — it works, and
it ships in every version of vanilla Minecraft. It is a design that was never revisited.

ChunkOptimizer replaces it.

---

## How It Works

**Motion-vector spiral dispatch**

Each dispatch batch is built by scoring pending chunks against the player's smoothed
movement vector, anchored to the last dispatched chunk rather than the player's current
position. This creates a chain — each batch picks up where the last one left off, biased
toward the direction of travel. When the player is stationary or the motion prediction
misses, a nearest-first fallback fills the remaining slots. The result is a spiral that
follows movement without wasting effort on chunks the player is moving away from.

Player motion is smoothed using an exponential moving average across ticks to eliminate
single-tick jitter from strafing or brief direction changes.

**Adaptive cadence**

Dispatch rate is not fixed. At world load or after a teleport, the system starts slow —
5 chunks every 300ms — and warms up as the render distance fills in, tightening to 3
chunks every 80ms once the area is largely loaded. When the player sprints or flies,
a speed mode engages that locks the interval floor to 100ms regardless of warmup state,
keeping the pipeline aggressive while moving fast. Batch size and interval shift together
at a 200ms threshold so throughput stays constant across mode transitions.

**Edge ticketer**

Once the pending queue drains, the dispatcher reaches past the current view distance
and issues a fan of three force-load tickets — one centered on the motion vector, one
spread left, one right — at two chunks beyond the render edge. This seeds world
generation before the player arrives, so the generation cost happens in the background
rather than at arrival. The ticketer only activates when the loaded ratio exceeds 30% of
the render distance — below that threshold, the hardware is already behind and edge
tickets add pressure without benefit. Tickets are cleaned up automatically as the player
advances past them.

**Per-player isolation**

Everything runs per `PlayerChunkSender` instance. Each player on a server gets their own
dispatch state — their own motion vector, their own cadence, their own edge tickets. They
don't share a pipeline or compete for the same dispatch budget. Players at established
bases with full render distances loaded generate almost no dispatch pressure. Players
actively exploring get aggressive, motion-aware dispatch. The server's generation budget
flows naturally toward whoever needs it.

---

## Example: Vanilla vs ChunkOptimizer

The core problem and solution, side by side.

### The vanilla collector

```java
// From PlayerChunkSender — runs every tick, throws result away
private List<LevelChunk> collectChunksToSend(ChunkMap chunkMap, ChunkPos chunkPos) {
    int i = Mth.floor(this.batchQuota);
    List<LevelChunk> list;
    if (!this.memoryConnection && this.pendingChunks.size() > i) {
        // Partial sort of the entire pending set — O(n log k), stateless
        list = this.pendingChunks
            .stream()
            .collect(Comparators.least(i, Comparator.comparingInt(chunkPos::distanceSquared)))
            .stream()
            .mapToLong(Long::longValue)
            .mapToObj(chunkMap::getChunkToSend)
            .filter(Objects::nonNull) // silent batch shrink — no backfill
            .toList();
    } else {
        // Full sort of the entire pending set — O(n log n), stateless
        list = this.pendingChunks
            .longStream()
            .mapToObj(chunkMap::getChunkToSend)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(p -> chunkPos.distanceSquared(p.getPos())))
            .toList();
    }
    // Result discarded next tick. No memory. No direction. Repeat.
    return list;
}
```

Problems visible here:
- Full re-sort of `pendingChunks` every tick with no state carried forward
- `filter(Objects::nonNull)` silently shrinks the batch — no backfill, no signal
- Hash-based iteration order means sort input is non-deterministic
- No awareness of player movement direction
- No cadence control — vanilla gates on `batchQuota` alone, which is reactive not proactive

### The ChunkOptimizer replacement

```java
// Injected at HEAD of collectChunksToSend — cancels vanilla, substitutes our list
@Inject(method = "collectChunksToSend", at = @At("HEAD"), cancellable = true)
private void chunkoptimizer_spiralCollect(ChunkMap chunkMap, ChunkPos playerChunkPos,
                                          CallbackInfoReturnable<List<LevelChunk>> cir) {
    used.clear();
    if (pendingChunks.isEmpty()) {
        cir.setReturnValue(List.of());
        return;
    }

    int batchSize = currentIntervalMs <= 200L ? 3 : 5;
    int motionSlots = batchSize / 2;

    // Chain anchor: last dispatched chunk, not player position
    // This creates the spiral — each batch continues from where the last ended
    ChunkPos anchor = prevChunkPacked != Long.MIN_VALUE
            ? new ChunkPos(prevChunkPacked)
            : playerChunkPos;

    boolean hasMotion = (motionDx * motionDx + motionDz * motionDz) > 0.01F;
    List<LevelChunk> result = new ArrayList<>(batchSize);

    // Step 1: pick chunks aligned with motion vector from the anchor
    if (hasMotion) {
        pickMotionAligned(chunkMap, anchor, motionSlots, used, result);
    }

    // Step 2: nearest-fill for remaining slots (spiral fallback when stopped)
    int remaining = batchSize - result.size();
    if (remaining > 0) {
        pickNearest(chunkMap, playerChunkPos, remaining, used, result);
    }

    // Advance the chain anchor to the last chunk we dispatched
    for (LevelChunk chunk : result) {
        pendingChunks.remove(chunk.getPos().toLong());
    }
    if (!result.isEmpty()) {
        prevChunkPacked = result.getLast().getPos().toLong();
    }

    cir.setReturnValue(result);
}
```

What changed:
- Stateful anchor chains each batch off the previous dispatch position
- Motion vector scoring replaces distance-only sort
- `used` set (hoisted `LongOpenHashSet`, cleared per cycle) deduplicates without allocation
- The cadence gate controls batch size and interval upstream — no silent shrink
- All packed coordinates stay as primitive `long` throughout — zero boxing, zero GC pressure

---

## Performance

> *Performance was calibrated to make the system collect pending chunks optimally without fighting*
*what is being sent to the generation thread. ChunkOptimizer reduces TPS inconsistencies and prevents flooding*
*the main thread with pending operations via adaptive load balancing.*

**Load is designed to accommodate clients according to the context of their loaded world, it**
**captures render state like view radius to prevent ticketing on slow clients and monitors**
**motion vectors to focus directly toward loading chunks cleanly in-front of the players.**

Tested on:
- AMD Ryzen 7 7800X3D / RTX 4070 Super / 22GB heap (ZGC)
- NeoForge 21.1.x clean world
- NeoForge 21.1.x 307-mod pack with C2ME, Sodium, Iris

Results:
- Clean world, no shaders: 6ms min / 6ms avg / 6ms max frame time during sprint-speed flight
- ~300-mod pack, no shaders: pC (pending chunks) holds at 000
- Solas medium: 4ms min / 6ms avg / 8ms max client frame time
- Solas ultra: 5ms min / 6ms avg / 10ms max client frame time 

**Could not outpace the loader at any flight speed in any tested world type**

---

## Compatibility

**C2ME** — Complementary. C2ME parallelizes world generation; ChunkOptimizer controls
dispatch ordering and cadence. They operate at different layers of the chunk pipeline and
do not conflict. The edge ticketer feeds C2ME's generation thread pool earlier, which
C2ME processes faster. Running both is recommended.

**ChunkFast** — ChunkOptimizer supersedes ChunkFast for live gameplay. The edge ticketer
covers the same use case as a chunk preloader for active sessions. For offline
pre-generation of large areas before opening a server, ChunkFast remains valid but is
otherwise unnecessary alongside ChunkOptimizer.

**Other optimization mods** — Check for mods in your pack that also modify chunk loading
or sending behavior. Redundant chunk optimizers targeting the same pipeline will fight
each other. Remove duplicates before benchmarking.

---

## JVM Recommendations

For best results, especially on modpacks:

```
-Xms18G -Xmx18G
-XX:+UseZGC
-XX:+ZGenerational
-XX:+AlwaysPreTouch
-XX:+DisableExplicitGC
-XX:+ParallelRefProcEnabled
-XX:+PerfDisableSharedMem
--add-modules=jdk.incubator.vector
-Dfml.readTimeout=90
-Dfml.loginTimeout=90
```

Adjust heap size to your available RAM. Leave at least 6GB free for the OS and GPU.
ZGC is recommended over G1GC for heavy modpacks — sub-millisecond GC pauses prevent
the GC spikes that compound with Sodium section rebuilds during fast movement.

`--add-modules=jdk.incubator.vector` enables SIMD acceleration for C2ME on Java 21.
Safe to include even without C2ME — it has no effect if C2ME is absent.

---

## License

MIT. Do what you want. Direct copies without modifications violate Modrinth content terms.
