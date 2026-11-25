package com.kaizzinho.wildbosses.boss

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.kaizzinho.wildbosses.WildBosses
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * Boss state survives restarts through the entity's own NBT (see PokemonEntityMixin) plus
 * setPersistenceRequired(), so vanilla autosave and chunk unloading write it like any other entity.
 * This class never forces a level save: doing so from a tick event re-ran the full world save
 * (including the overworld's SavedData files) outside of vanilla's save schedule and could collide
 * with a save already in progress.
 */
object BossPersistence {

    private const val SPAWN_TICK_TAG_PREFIX = "wildbosses:spawned_at_"
    private val pendingClientResyncs = linkedMapOf<UUID, PendingClientResync>()

    private data class PendingClientResync(
        val entityUuid: UUID,
        val level: ServerLevel,
        val tier: BossTier,
        val spawnedAtTick: Long
    )

    fun register() {
        ServerLifecycleEvents.SERVER_STOPPED.register {
            pendingClientResyncs.clear()
            BossRegistry.clear()
        }

        ServerTickEvents.END_SERVER_TICK.register resyncTick@{ _ ->
            if (pendingClientResyncs.isEmpty()) return@resyncTick

            val resyncs = pendingClientResyncs.values.toList()
            pendingClientResyncs.clear()
            for (resync in resyncs) {
                val entity = resync.level.getEntity(resync.entityUuid) as? PokemonEntity ?: continue
                if (entity.isRemoved) continue

                restoreRuntimeState(entity, resync.tier, resync.spawnedAtTick)
                entity.entityData.set(PokemonEntity.LABEL_LEVEL, -1, true)
                entity.setGlowingTag(false)
                entity.setGlowingTag(true)

                logState("POST_TRACKING_RESYNC", entity, "tier=${resync.tier.name}")
            }
        }
    }

    fun mark(entity: PokemonEntity, spawnedAtTick: Long) {
        entity.setPersistenceRequired()
        entity.tags.removeIf { it.startsWith(SPAWN_TICK_TAG_PREFIX) }
        entity.tags.add("$SPAWN_TICK_TAG_PREFIX$spawnedAtTick")
    }

    fun restoreRuntimeState(entity: PokemonEntity, tier: BossTier, spawnedAtTick: Long) {
        mark(entity, spawnedAtTick)

        entity.tags.add("wildbosses:is_boss")
        entity.tags.removeIf { it.startsWith("wildbosses:tier_") }
        entity.tags.add("wildbosses:tier_${tier.name}")

        // tags and team keep boss state without custom synced ids
        entity.entityData.set(PokemonEntity.LABEL_LEVEL, -1)

        val scoreboard = entity.server?.scoreboard
        if (scoreboard != null) {
            val teamName = "wildbosses_${tier.name.lowercase()}"
            val team = scoreboard.getPlayerTeam(teamName) ?: scoreboard.addPlayerTeam(teamName)
            team.setColor(tier.color)
            scoreboard.addPlayerToTeam(entity.scoreboardName, team)
        }

        entity.setGlowingTag(true)
    }

    /** Makes sure the boss is never despawned; vanilla saves it on the next autosave or chunk unload. */
    fun ensurePersistent(entity: PokemonEntity) {
        entity.setPersistenceRequired()
        logState("ENSURE_PERSISTENT", entity)
    }

    fun requestClientResync(entity: PokemonEntity, tier: BossTier, spawnedAtTick: Long) {
        val level = entity.level() as? ServerLevel ?: return
        pendingClientResyncs[entity.uuid] = PendingClientResync(entity.uuid, level, tier, spawnedAtTick)
        logState("TRACKING_RESYNC_QUEUED", entity, "tier=${tier.name}")
    }

    fun readSpawnedAtTick(entity: PokemonEntity): Long? =
        entity.tags.firstOrNull { it.startsWith(SPAWN_TICK_TAG_PREFIX) }
            ?.removePrefix(SPAWN_TICK_TAG_PREFIX)
            ?.toLongOrNull()

    fun logState(phase: String, entity: PokemonEntity, extra: String = "") {
        if (!WildBosses.logger.isDebugEnabled) return

        val labelLevel = runCatching { entity.entityData.get(PokemonEntity.LABEL_LEVEL) }.getOrNull()
        val tierTag = entity.tags.firstOrNull { it.startsWith("wildbosses:tier_") }
        val spawnTag = entity.tags.firstOrNull { it.startsWith(SPAWN_TICK_TAG_PREFIX) }
        val teamName = entity.team?.name ?: "<none>"
        val suffix = if (extra.isBlank()) "" else " $extra"

        WildBosses.logger.debug(
            "[BossPersistence] $phase species=${entity.pokemon.species.name} " +
                "entityUuid=${entity.uuid} pokemonUuid=${entity.pokemon.uuid} " +
                "registry=${BossRegistry.isBoss(entity.uuid)} bossTag=${entity.tags.contains("wildbosses:is_boss")} " +
                "tierTag=$tierTag spawnTag=$spawnTag team=$teamName pokemonLevel=${entity.pokemon.level} " +
                "labelLevel=$labelLevel glowing=${entity.isCurrentlyGlowing()} " +
                "persistenceRequired=${entity.isPersistenceRequired()} removed=${entity.isRemoved} " +
                "dimension=${(entity.level() as? ServerLevel)?.dimension()?.location()} pos=${entity.blockPosition()}$suffix"
        )
    }
}
