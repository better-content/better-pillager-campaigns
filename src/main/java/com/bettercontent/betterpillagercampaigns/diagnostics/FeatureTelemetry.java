package com.bettercontent.betterpillagercampaigns.diagnostics;

/** Pure observation of the owning feature's existing outcome, with no native reads. */
public final class FeatureTelemetry {
    private FeatureTelemetry() {}
    public static void rejected(PlaytestLog log, PlaytestLog.Context context, String invasion, int wave, String effect, String reason) {
        log.debug("materialization", "campaign.packet", context, "rejected", reason,
                () -> PlaytestLog.fields("invasion", invasion, "wave", wave, "effect", effect));
    }
    public static void packet(PlaytestLog log, String invasion, int wave, int members, String effect) {
        log.event("materialization", "campaign.packet", "server", "inserted", "native_insertion", () -> PlaytestLog.fields("invasion", invasion, "wave", wave, "members", members, "effect", effect));
    }
}
