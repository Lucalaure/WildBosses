package com.kaizzinho.wildbosses.boss

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.evolution.Evolution
import com.cobblemon.mod.common.pokemon.FormData
import com.cobblemon.mod.common.pokemon.Pokemon
import com.cobblemon.mod.common.pokemon.Species
import com.cobblemon.mod.common.pokemon.requirements.LevelRequirement
import com.cobblemon.mod.common.pokemon.requirements.PokemonPropertiesRequirement
import com.cobblemon.mod.common.util.asIdentifierDefaultingNamespace
import com.kaizzinho.wildbosses.WildBosses
import com.kaizzinho.wildbosses.config.WildBossesConfig

object BossEvolutionResolver {

    data class ResolvedForm(val species: Species, val form: FormData)

    private data class EvolutionOption(val evolution: Evolution, val target: ResolvedForm)

    fun resolveFinalForm(startingSpecies: Species, startingForm: FormData): ResolvedForm {
        var current = ResolvedForm(startingSpecies, startingForm)
        val visited = mutableSetOf(keyOf(current))

        while (true) {
            val next = evolutionOptions(current.form).randomOrNull()?.target ?: return current
            if (!visited.add(keyOf(next))) return current
            current = next
        }
    }

    /**
     * Evolves [pokemon] through every stage it qualifies for at [level], ignoring items, trades,
     * friendship and other conditions. Evolutions without a level requirement count as reachable
     * at the configured fallback level. Returns true if the species changed.
     */
    fun evolveForLevel(pokemon: Pokemon, level: Int): Boolean {
        val config = WildBossesConfig.data
        if (!config.forceEvolveBosses) return false

        val startName = pokemon.species.name
        var current = ResolvedForm(pokemon.species, pokemon.form)
        val visited = mutableSetOf(keyOf(current))

        while (true) {
            val reachable = evolutionOptions(current.form).filter { option ->
                requiredLevel(option.evolution, config.forcedEvolutionFallbackLevel) <= level &&
                    genderAllows(option.evolution, pokemon)
            }
            val next = reachable.randomOrNull()?.target ?: break
            if (!visited.add(keyOf(next))) break
            current = next
        }

        if (current.species == pokemon.species && current.form == pokemon.form) return false

        pokemon.species = current.species
        pokemon.form = current.form
        pokemon.updateAspects()
        WildBosses.logger.debug(
            "[BossEvolution] Forced $startName -> ${current.species.name} (${current.form.name}) at level $level"
        )
        return true
    }

    private fun evolutionOptions(form: FormData): List<EvolutionOption> =
        form.evolutions.mapNotNull { evolution ->
            val speciesName = evolution.result.species ?: return@mapNotNull null
            val targetSpecies = PokemonSpecies.getByIdentifier(speciesName.asIdentifierDefaultingNamespace())
                ?: return@mapNotNull null
            if (targetSpecies.labels.any { it in WildBossesConfig.data.excludedSpeciesLabels }) {
                return@mapNotNull null
            }
            val formName = evolution.result.form
            val targetForm = formName
                ?.let { name -> targetSpecies.forms.find { it.name.equals(name, ignoreCase = true) } }
                ?: targetSpecies.standardForm
            EvolutionOption(evolution, ResolvedForm(targetSpecies, targetForm))
        }

    private fun requiredLevel(evolution: Evolution, fallbackLevel: Int): Int {
        val minLevel = evolution.requirements
            .filterIsInstance<LevelRequirement>()
            .maxOfOrNull { it.minLevel }
        // a level of 1 is set on some item or trade evolutions and is not a real level gate
        return if (minLevel != null && minLevel > 1) minLevel else fallbackLevel
    }

    // keep gender-locked branches (Gallade, Froslass, ...) on the right gender
    private fun genderAllows(evolution: Evolution, pokemon: Pokemon): Boolean =
        evolution.requirements
            .filterIsInstance<PokemonPropertiesRequirement>()
            .all { requirement -> requirement.target.gender.let { it == null || it == pokemon.gender } }

    private fun keyOf(form: ResolvedForm): String = "${form.species.name}:${form.form.name}"
}
