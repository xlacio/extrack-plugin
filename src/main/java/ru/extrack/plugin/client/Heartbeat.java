package ru.extrack.plugin.client;

import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import ru.extrack.plugin.platform.Compat;

/** A snapshot of the server state. On Paper it is taken on the main thread, on Folia anywhere. */
final class Heartbeat {

    private static final int MAX_PLAYERS_LISTED = 20_000;

    private final int        maxPlayers;
    private final Double     tps;
    private final Double     mspt;
    private final long       ramUsed;
    private final long       ramMax;
    private final Integer    chunks;
    private final Integer    entities;
    private final List<UUID> players;

    private Heartbeat(int maxPlayers, Double tps, Double mspt, long ramUsed, long ramMax, Integer chunks, Integer entities, List<UUID> players) {
        this.maxPlayers = maxPlayers;
        this.tps = tps;
        this.mspt = mspt;
        this.ramUsed = ramUsed;
        this.ramMax = ramMax;
        this.chunks = chunks;
        this.entities = entities;
        this.players = players;
    }

    static Heartbeat collect(boolean folia) {
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        List<UUID> players = new ArrayList<>(Math.min(online.size(), MAX_PLAYERS_LISTED));
        for (Player player : online) {
            if (players.size() == MAX_PLAYERS_LISTED) break;
            players.add(player.getUniqueId());
        }

        Integer chunks = null;
        Integer entities = null;
        if (!folia) {
            // region threads own the worlds on Folia, counting there is not safe
            int chunkTotal = 0;
            int entityTotal = 0;
            boolean supported = true;
            for (World world : Bukkit.getWorlds()) {
                Integer chunkCount = Compat.chunkCount(world);
                Integer entityCount = Compat.entityCount(world);
                if (chunkCount == null || entityCount == null) {
                    supported = false;
                    break;
                }
                chunkTotal += chunkCount;
                entityTotal += entityCount;
            }
            if (supported) {
                chunks = chunkTotal;
                entities = entityTotal;
            }
        }

        Runtime runtime = Runtime.getRuntime();
        long ramUsed = (runtime.totalMemory() - runtime.freeMemory()) >> 20;
        long ramMax = Math.min(runtime.maxMemory() >> 20, 10_000_000L);
        return new Heartbeat(Bukkit.getMaxPlayers(), Compat.tps(folia), folia ? null : Compat.averageTickTime(),
            ramUsed, ramMax, chunks, entities, players);
    }

    void write(JsonWriter out) throws IOException {
        out.beginObject();
        out.name("online").value(this.players.size());
        out.name("maxPlayers").value(Math.max(0, Math.min(1_000_000, this.maxPlayers)));
        if (this.tps != null) out.name("tps").value(this.tps);
        if (this.mspt != null) out.name("mspt").value(Math.min(100_000D, this.mspt));
        out.name("ramUsed").value(this.ramUsed);
        out.name("ramMax").value(this.ramMax);
        if (this.chunks != null) out.name("chunks").value(this.chunks);
        if (this.entities != null) out.name("entities").value(this.entities);
        out.name("players").beginArray();
        for (UUID uuid : this.players) {
            out.value(uuid.toString());
        }
        out.endArray();
        out.endObject();
    }
}
