package com.chunkoptimizer;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ChunkOptimizerConfig {

    public enum Preset { SMOOTH, PERFORMANCE }

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.EnumValue<Preset> PRESET = BUILDER
            .comment(
                    "SMOOTH — high-end hardware, tightest cadence, most aggressive edge ticketing. " +
                            "PERFORMANCE — lower-end hardware or large servers, wider intervals, conservative ticketing"
            )
            .defineEnum("preset", Preset.SMOOTH);

    public static final ModConfigSpec SPEC = BUILDER.build();

    // Resolved values — read by the mixin
    public static long   startIntervalMs;
    public static long   endIntervalMs;
    public static int    warmBatchSize;
    public static int    fastBatchSize;
    public static long   sprintIntervalMs;
    public static long   ticketerIntervalMs;
    public static float  ticketStopRatio;
    public static int    maxEdgeTickets;

    public static void apply() {
        switch (PRESET.get()) {
            case SMOOTH -> {
                startIntervalMs    = 300L;
                endIntervalMs      = 50L;
                warmBatchSize      = 5;
                fastBatchSize      = 3;
                sprintIntervalMs   = 100L;
                ticketerIntervalMs = 200L;
                ticketStopRatio    = 0.30F;
                maxEdgeTickets     = 72;
            }
            case PERFORMANCE -> {
                startIntervalMs    = 500L;
                endIntervalMs      = 150L;
                warmBatchSize      = 3;
                fastBatchSize      = 2;
                sprintIntervalMs   = 200L;
                ticketerIntervalMs = 500L;
                ticketStopRatio    = 0.50F;
                maxEdgeTickets     = 24;
            }
        }
    }
}