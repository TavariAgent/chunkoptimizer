package com.chunkoptimizer.mixin;

import com.chunkoptimizer.ChunkOptimizerConfig;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Mixin(PlayerChunkSender.class)
public class PlayerChunkSenderMixin {

    @Shadow private LongSet pendingChunks;
    @Shadow @Mutable private float batchQuota;

    // Dispatch cadence
    @Unique private long lastDispatchTime = 0L;
    @Unique private long currentIntervalMs = 300L;

    // Spiral chain state
    @Unique private long prevChunkPacked = Long.MIN_VALUE;
    @Unique private float motionDx = 0.0F;
    @Unique private float motionDz = 0.0F;

    // Cadence speed locking state
    @Unique private static final float SPRINT_SPEED_THRESHOLD = 0.12F;

    // Edge ticketer state
    @Unique private final LongOpenHashSet edgeTickets = new LongOpenHashSet(96);
    @Unique private long lastTicketerTime = 0L;
    @Unique private final LongOpenHashSet used = new LongOpenHashSet(8);

    @Unique
    private void tickEdge(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        int viewDist = level.getServer().getPlayerList().getViewDistance();
        int totalExpected = (2 * viewDist + 1) * (2 * viewDist + 1);
        float ratio = totalExpected > 0
                ? Mth.clamp(1.0F - ((float) pendingChunks.size() / totalExpected), 0.0F, 1.0F)
                : 1.0F;

        if (ratio < ChunkOptimizerConfig.ticketStopRatio) return;
        ChunkPos playerChunk = player.chunkPosition();

        // Remove tickets the player has advanced past
        LongIterator iter = edgeTickets.iterator();
        while (iter.hasNext()) {
            long packed = iter.nextLong();
            int dx = ChunkPos.getX(packed) - playerChunk.x;
            int dz = ChunkPos.getZ(packed) - playerChunk.z;
            if (dx * dx + dz * dz <= (viewDist + 1) * (viewDist + 1)) {
                level.getChunkSource().removeRegionTicket(
                        TicketType.FORCED, new ChunkPos(packed), 2, new ChunkPos(packed)
                );
                iter.remove();
            }
        }

        if (edgeTickets.size() >= ChunkOptimizerConfig.maxEdgeTickets) return;
        if (motionDx * motionDx + motionDz * motionDz <= 0.01F) return;

        // Fan: center, one chunk left, one chunk right of motion vector
        // Perpendicular to (motionDx, motionDz) is (-motionDz, motionDx)
        int edgeDist = viewDist + 2;
        float[] fanDx = {
                motionDx * edgeDist,
                motionDx * edgeDist - motionDz * 2,
                motionDx * edgeDist + motionDz * 2
        };
        float[] fanDz = {
                motionDz * edgeDist,
                motionDz * edgeDist + motionDx * 2,
                motionDz * edgeDist - motionDx * 2
        };

        for (int i = 0; i < 3; i++) {
            int cx = playerChunk.x + Math.round(fanDx[i]);
            int cz = playerChunk.z + Math.round(fanDz[i]);
            long packed = ChunkPos.asLong(cx, cz);

            if (!edgeTickets.contains(packed)) {
                ChunkPos edgePos = new ChunkPos(packed);
                level.getChunkSource().addRegionTicket(
                        TicketType.FORCED, edgePos, 2, edgePos
                );
                edgeTickets.add(packed);
            }
        }
    }

    @Inject(method = "sendNextChunks", at = @At("HEAD"), cancellable = true)
    private void chunkoptimizer_gateDispatch(ServerPlayer player, CallbackInfo ci) {
        long now = System.currentTimeMillis();
        if (now - lastDispatchTime < currentIntervalMs) {
            ci.cancel();
            return;
        }
        lastDispatchTime = now;

        // Smooth player motion vector — EMA blend to kill single-tick jitter
        Vec3 motion = player.getDeltaMovement();
        float rawDx = (float) motion.x;
        float rawDz = (float) motion.z;
        float speed = (float) Math.sqrt(rawDx * rawDx + rawDz * rawDz);
        if (speed > 0.05F) {
            float inv = 1.0F / speed;
            motionDx = motionDx * 0.7F + (rawDx * inv) * 0.3F;
            motionDz = motionDz * 0.7F + (rawDz * inv) * 0.3F;
        }

        updateInterval(player);

        if (pendingChunks.isEmpty()) {
            long now2 = System.currentTimeMillis();
            if (now2 - lastTicketerTime >= ChunkOptimizerConfig.ticketerIntervalMs) {
                lastTicketerTime = now2;
                tickEdge(player);
            }
        }

        // Force batchQuota high enough that vanilla's >= 1.0 check always passes
        // when our timer says go — collectChunksToSend controls actual count
        batchQuota = currentIntervalMs <= 200L
                ? ChunkOptimizerConfig.fastBatchSize
                : ChunkOptimizerConfig.warmBatchSize;
    }

    @Inject(method = "collectChunksToSend", at = @At("HEAD"), cancellable = true)
    private void chunkoptimizer_spiralCollect(ChunkMap chunkMap, ChunkPos playerChunkPos,
                                              CallbackInfoReturnable<List<LevelChunk>> cir) {
        used.clear();
        if (pendingChunks.isEmpty()) {
            cir.setReturnValue(List.of());
            return;
        }

        int batchSize = currentIntervalMs <= 200L
                ? ChunkOptimizerConfig.fastBatchSize
                : ChunkOptimizerConfig.warmBatchSize;
        int motionSlots = batchSize / 2; // 1 or 2 motion-predicted slots

        // Chain from last dispatched chunk; fall back to player pos on first dispatch
        ChunkPos anchor = prevChunkPacked != Long.MIN_VALUE
                ? new ChunkPos(prevChunkPacked)
                : playerChunkPos;

        boolean hasMotion = (motionDx * motionDx + motionDz * motionDz) > 0.01F;

        List<LevelChunk> result = new ArrayList<>(batchSize);

        // Step 1: motion-vector aligned picks from anchor
        if (hasMotion) {
            pickMotionAligned(chunkMap, anchor, motionSlots, used, result);
        }

        // Step 2: nearest-fill for remaining slots (spiral fallback)
        int remaining = batchSize - result.size();
        if (remaining > 0) {
            pickNearest(chunkMap, playerChunkPos, remaining, used, result);
        }

        // Remove dispatched from pending and advance chain anchor
        for (LevelChunk chunk : result) {
            pendingChunks.remove(chunk.getPos().toLong());
        }
        if (!result.isEmpty()) {
            prevChunkPacked = result.getLast().getPos().toLong();
        }

        cir.setReturnValue(result);
    }

    @Unique
    private void updateInterval(ServerPlayer player) {
        Vec3 motion = player.getDeltaMovement();
        float speed = (float)(motion.x * motion.x + motion.z * motion.z);

        int viewDist = player.serverLevel().getServer().getPlayerList().getViewDistance();
        int totalExpected = (2 * viewDist + 1) * (2 * viewDist + 1);
        float ratio = totalExpected > 0
                ? Mth.clamp(1.0F - ((float) pendingChunks.size() / totalExpected), 0.0F, 1.0F)
                : 1.0F;

        long warmInterval = (long) Mth.lerp(ratio,
                ChunkOptimizerConfig.startIntervalMs,
                ChunkOptimizerConfig.endIntervalMs);

        // Speed mode: lock floor at 150ms when sprinting/elytra
        if (speed > SPRINT_SPEED_THRESHOLD * SPRINT_SPEED_THRESHOLD) {
            currentIntervalMs = Math.min(warmInterval, ChunkOptimizerConfig.sprintIntervalMs);
        } else {
            currentIntervalMs = warmInterval;
        }
    }

    @Unique
    private void pickMotionAligned(ChunkMap chunkMap, ChunkPos anchor,
                                   int slots, LongOpenHashSet used, List<LevelChunk> out) {
        long[] topPacked = new long[slots];
        float[] topScores = new float[slots];
        Arrays.fill(topPacked, Long.MIN_VALUE);
        Arrays.fill(topScores, Float.NEGATIVE_INFINITY);

        LongIterator iter = pendingChunks.iterator();
        while (iter.hasNext()) {
            long packed = iter.nextLong();
            if (used.contains(packed)) continue;
            float relX = ChunkPos.getX(packed) - anchor.x;
            float relZ = ChunkPos.getZ(packed) - anchor.z;
            float distSq = relX * relX + relZ * relZ;
            if (distSq < 0.01F) continue;
            float invDist = (float) (1.0 / Math.sqrt(distSq));
            // Alignment with motion direction, light distance penalty
            float score = ((relX * invDist) * motionDx + (relZ * invDist) * motionDz)
                    - distSq * 0.005F;

            // Top-N insertion — slots are 1 or 2, linear scan is trivially inexpensive
            for (int i = 0; i < slots; i++) {
                if (score > topScores[i]) {
                    for (int j = slots - 1; j > i; j--) {
                        topScores[j] = topScores[j - 1];
                        topPacked[j] = topPacked[j - 1];
                    }
                    topScores[i] = score;
                    topPacked[i] = packed;
                    break;
                }
            }
        }

        for (int i = 0; i < slots; i++) {
            if (topPacked[i] == Long.MIN_VALUE) break;
            used.add(topPacked[i]);
            LevelChunk chunk = chunkMap.getChunkToSend(topPacked[i]);
            if (chunk != null) out.add(chunk);
        }
    }

    @Unique
    private void pickNearest(ChunkMap chunkMap, ChunkPos anchor,
                             int slots, LongOpenHashSet used, List<LevelChunk> out) {
        long[] topPacked = new long[slots];
        int[] topDist = new int[slots];
        Arrays.fill(topPacked, Long.MIN_VALUE);
        Arrays.fill(topDist, Integer.MAX_VALUE);

        LongIterator iter = pendingChunks.iterator();
        while (iter.hasNext()) {
            long packed = iter.nextLong();
            if (used.contains(packed)) continue;
            int dx = ChunkPos.getX(packed) - anchor.x;
            int dz = ChunkPos.getZ(packed) - anchor.z;
            int dist = dx * dx + dz * dz;

            for (int i = 0; i < slots; i++) {
                if (dist < topDist[i]) {
                    for (int j = slots - 1; j > i; j--) {
                        topDist[j] = topDist[j - 1];
                        topPacked[j] = topPacked[j - 1];
                    }
                    topDist[i] = dist;
                    topPacked[i] = packed;
                    break;
                }
            }
        }

        for (int i = 0; i < slots; i++) {
            if (topPacked[i] == Long.MIN_VALUE) break;
            used.add(topPacked[i]);
            LevelChunk chunk = chunkMap.getChunkToSend(topPacked[i]);
            if (chunk != null) out.add(chunk);
        }
    }
}