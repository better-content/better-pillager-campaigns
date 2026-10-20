package com.bettercontent.betterpillagercampaigns;

import com.bettercontent.betterpillagercampaigns.diagnostics.PlaytestLog;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/** Native lifecycle adapter; retains no level, player or entity references. */
@Mod.EventBusSubscriber(modid = "better_pillager_campaigns")
public final class PlaytestDiagnostics {
    public static final PlaytestLog LOG = new PlaytestLog("better_pillager_campaigns");
    private PlaytestDiagnostics() {}

    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) LOG.summary("server");
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { LOG.reset("server"); }

    @Mod.EventBusSubscriber(modid = "better_pillager_campaigns", value = net.minecraftforge.api.distmarker.Dist.CLIENT)
    public static final class ClientLifecycle {
        @SubscribeEvent public static void disconnect(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
            LOG.reset("client");
        }
    }

    @Mod.EventBusSubscriber(modid = "better_pillager_campaigns", bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Startup {
        @SubscribeEvent public static void setup(FMLCommonSetupEvent event) {
            String version = net.minecraftforge.fml.ModList.get().getModContainerById("better_pillager_campaigns")
                    .map(container -> container.getModInfo().getVersion().toString()).orElse("UNKNOWN");
            var modFile = net.minecraftforge.fml.ModList.get().getModFileById("better_pillager_campaigns");
            String source = modFile == null ? "UNKNOWN" : LOG.sourceIdentity(modFile.getFile().findResource("META-INF", "better-content-source.properties"));
            LOG.status("common", version, source, () -> PlaytestLog.fields(
                    "enabled", PillagerCampaignsConfig.INSTANCE.getEnabled().get(), "enabled_source", "common_config",
                    "integration_door", net.minecraftforge.fml.ModList.get().isLoaded("better_deaths_door"),
                    "integration_pillage", net.minecraftforge.fml.ModList.get().isLoaded("takesapillage"),
                    "integration_savage", net.minecraftforge.fml.ModList.get().isLoaded("savage_and_ravage")));
        }
    }
}
