package com.kaizzinho.wildbosses.battle

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.moves.categories.DamageCategories
import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.api.pokemon.moves.Learnset
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.api.types.ElementalType
import com.cobblemon.mod.common.api.types.ElementalTypes
import com.cobblemon.mod.common.battles.ai.strongBattleAI.AIUtility
import com.cobblemon.mod.common.pokemon.Pokemon
import com.kaizzinho.wildbosses.WildBosses
import com.kaizzinho.wildbosses.boss.BossTier
import java.util.UUID
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

object BossMovesetBuilder {
    private val SELF_DESTRUCT_MOVES = setOf(
        "explosion", "selfdestruct", "mindblown", "mistyexplosion", "chloroblast", "steelbeam"
    )

    private val RECHARGE_MOVES = setOf(
        "hyperbeam", "gigaimpact", "blastburn", "hydrocannon", "frenzyplant",
        "rockwrecker", "roaroftime", "eternabeam"
    )

    private val CHARGE_TURN_MOVES = setOf(
        "solarbeam", "solarblade", "skyattack", "skullbash", "razorwind",
        "dig", "fly", "bounce", "dive", "shadowforce", "phantomforce",
        "freezeshock", "iceburn", "meteorbeam", "electroshot", "skydrop"
    )

    private val LOCKED_IN_MOVES = setOf(
        "uproar", "thrash", "petaldance", "outrage", "rollout", "iceball"
    )

    // skip attacks that can just whiff without their setup condition
    private val CONDITIONAL_FAIL_MOVES = setOf(
        "dreameater", "lastresort", "synchronoise", "focuspunch",
        "counter", "mirrorcoat", "metalburst", "bide", "hiddenpower", "upperhand",
        "steelroller"
    )

    // attacks that hurt the user for a share of the damage dealt
    private val RECOIL_MOVES = setOf(
        "doubleedge", "bravebird", "flareblitz", "woodhammer", "headsmash", "wavecrash",
        "volttackle", "wildcharge", "takedown", "submission", "headcharge", "lightofruin"
    )

    val DELAYED_ATTACK_MOVES = setOf(
        "futuresight", "doomdesire"
    )

    val RISKY_SELF_NERF_MOVES = setOf(
        "dracometeor", "leafstorm", "overheat", "psychoboost", "fleurcannon",
        "superpower", "closecombat", "vcreate", "makeitrain", "headlongrush"
    )

    val AGGRESSIVE_SETUP_MOVES = setOf(
        "swordsdance", "dragondance", "bulkup", "coil", "honeclaws",
        "shiftgear", "victorydance", "shellsmash", "noretreat",
        "nastyplot", "calmmind", "quiverdance", "tailglow", "takeheart"
    )

    val RECOVERY_MOVES = setOf(
        "recover", "roost", "slackoff", "softboiled", "milkdrink",
        "healorder", "moonlight", "synthesis", "shoreup"
    )

    private val PHYSICAL_SETUP_MOVES = setOf(
        "shellsmash", "shiftgear", "dragondance", "swordsdance", "victorydance",
        "noretreat", "coil", "bulkup", "honeclaws"
    )

    private val SPECIAL_SETUP_MOVES = setOf(
        "quiverdance", "tailglow", "nastyplot", "shellsmash", "noretreat", "calmmind", "takeheart"
    )

    private val BLACKLISTED_MOVESET_SPECIES = setOf("ditto", "smeargle")

    // how much a move's score grows per share of all types it newly hits super effectively
    private const val COVERAGE_WEIGHT = 3.0

    // same-type picks after the first still score, but give way to new types
    private const val DUPLICATE_TYPE_FACTOR = 0.70

    private const val SOFTMAX_POOL = 4

    // a move counts as a signature move when this few species learn it naturally
    private const val SIGNATURE_MAX_LEARNERS = 3

    private const val NATURAL_MOVE_BONUS = 1.10
    private const val EGG_MOVE_BONUS = 1.04
    private const val SIGNATURE_MOVE_BONUS = 1.18
    private const val RECOIL_FACTOR = 0.90

    @Volatile
    private var naturalLearnerCache: Pair<Int, Map<String, Int>>? = null

    fun apply(bossPokemon: Pokemon, tier: BossTier, seed: UUID) {
        if (bossPokemon.species.showdownId() in BLACKLISTED_MOVESET_SPECIES) {
            WildBosses.logger.info("[BossAI] Keeping ${bossPokemon.species.name}'s natural moveset; custom movesets don't fit this species")
            return
        }

        // same boss, same rolls: fleeing and coming back (or a restart) keeps the moveset
        val rng = Random(seed.mostSignificantBits xor seed.leastSignificantBits)

        val attack = Cobblemon.statProvider.getStatForPokemon(bossPokemon, Stats.ATTACK).coerceAtLeast(1)
        val specialAttack = Cobblemon.statProvider.getStatForPokemon(bossPokemon, Stats.SPECIAL_ATTACK).coerceAtLeast(1)
        val favorsPhysical = attack >= specialAttack
        val bossTypes = bossPokemon.types.toSet()
        val severeWeaknesses = severeWeaknesses(bossTypes)
        val minimumAccuracy = minimumAccuracyFor(tier.aiSkill)

        val learnset = bossPokemon.species.moves
        val legalMoves = learnset.getAllLegalMoves()
            .distinctBy { it.name }
            .filterNot { it.name in BossBattleRestrictions.BOSS_AI_FORBIDDEN_MOVES }
        val attackCandidates = legalMoves
            .asSequence()
            .filter { it.damageCategory != DamageCategories.STATUS }
            .filter { it.power > 0.0 }
            .filter { it.accuracy <= 0.0 || it.accuracy >= minimumAccuracy }
            .filter { it.name !in SELF_DESTRUCT_MOVES }
            .filter { it.name !in RECHARGE_MOVES }
            .filter { it.name !in CHARGE_TURN_MOVES }
            .filter { it.name !in LOCKED_IN_MOVES }
            .filter { it.name !in CONDITIONAL_FAIL_MOVES }
            .toList()

        if (attackCandidates.isEmpty()) {
            WildBosses.logger.warn("[BossAI] No safe attacks found for ${bossPokemon.species.name}; keeping its natural moveset")
            return
        }

        val sourceBonus = sourceBonuses(learnset)
        val scores = attackCandidates.associateWith { move ->
            movesetScore(move, bossTypes, attack, specialAttack) * (sourceBonus[move.name] ?: 1.0)
        }

        val instinctMove = chooseInstinctMove(legalMoves, tier.aiSkill, favorsPhysical, rng)
        val attackSlots = if (instinctMove == null) 4 else 3
        val selectedAttacks = chooseAttacks(
            scores = scores,
            slots = attackSlots,
            bossTypes = bossTypes,
            aiSkill = tier.aiSkill,
            tier = tier,
            severeWeaknesses = severeWeaknesses,
            rng = rng
        )

        if (selectedAttacks.isEmpty()) {
            WildBosses.logger.warn("[BossAI] Couldn't build an attack set for ${bossPokemon.species.name}; keeping its natural moveset")
            return
        }

        val finalMoves = buildList {
            instinctMove?.let { add(it.name) }
            addAll(selectedAttacks.map { it.name })
        }.distinct().take(4)

        val properties = PokemonProperties()
        properties.moves = finalMoves
        properties.apply(bossPokemon)

        val style = offensiveStyle(attack, specialAttack)
        val instinct = instinctMove?.let { " instinct=${it.name}" } ?: ""
        val coverageMove = selectedAttacks.firstOrNull { move ->
            severeWeaknesses.any { threat -> AIUtility.getDamageMultiplier(move.elementalType, threat) > 1.0 }
        }
        val caution = if (coverageMove != null && severeWeaknesses.isNotEmpty()) {
            " caution=${severeWeaknesses.joinToString("/") { it.name }}->${coverageMove.name}"
        } else {
            ""
        }
        val covered = superEffectivelyHit(selectedAttacks.map { it.elementalType }).size
        WildBosses.logger.info(
            "[BossAI] ${tier.name} ${bossPokemon.species.name} moveset style=$style skill=${tier.aiSkill}$instinct$caution " +
                "coverage=$covered/${ElementalTypes.all().size} -> ${finalMoves.joinToString(" | ")}"
        )
    }

    private fun chooseInstinctMove(
        legalMoves: List<MoveTemplate>,
        aiSkill: Int,
        favorsPhysical: Boolean,
        rng: Random
    ): MoveTemplate? {
        if (aiSkill < 4) return null

        val preferredSetup = if (favorsPhysical) PHYSICAL_SETUP_MOVES else SPECIAL_SETUP_MOVES
        val setupOptions = legalMoves.filter { it.name in preferredSetup }.sortedBy { it.name }
        if (setupOptions.isNotEmpty()) return setupOptions.random(rng)

        if (aiSkill >= 5) {
            val recoveryOptions = legalMoves.filter { it.name in RECOVERY_MOVES }.sortedBy { it.name }
            if (recoveryOptions.isNotEmpty()) return recoveryOptions.random(rng)
        }

        return null
    }

    private fun chooseAttacks(
        scores: Map<MoveTemplate, Double>,
        slots: Int,
        bossTypes: Set<ElementalType>,
        aiSkill: Int,
        tier: BossTier,
        severeWeaknesses: List<ElementalType>,
        rng: Random
    ): List<MoveTemplate> {
        val ranked = scores.keys.sortedWith(compareByDescending<MoveTemplate> { scores.getValue(it) }.thenBy { it.name })
        val temperature = selectionTemperature(tier)
        val selected = mutableListOf<MoveTemplate>()
        val reservedCoverage = chooseThreatCoverageMove(ranked, scores, severeWeaknesses, tier)
            ?.takeIf { rng.nextDouble() < coverageReserveChance(tier) }
        reservedCoverage?.let(selected::add)

        // one STAB attack per boss type
        for (type in bossTypes.sortedBy { it.name }) {
            if (selected.size >= slots) break
            if (selected.any { it.elementalType == type }) continue
            val options = ranked.filter { it.elementalType == type }.map { it to scores.getValue(it) }
            softmaxPick(options, temperature, rng)?.let(selected::add)
        }

        // fill the rest by what each move adds to the set's super-effective reach
        while (selected.size < slots) {
            val covered = superEffectivelyHit(selected.map { it.elementalType })
            val usedTypes = selected.map { it.elementalType }.toSet()
            val options = ranked
                .filter { it !in selected }
                .map { move -> move to coverageAdjustedScore(move, scores.getValue(move), covered, usedTypes) }
            val pick = softmaxPick(options, temperature, rng) ?: break
            selected.add(pick)
        }

        if (aiSkill >= 4 && selected.none { it.priority > 0 }) {
            val priorityMove = ranked.firstOrNull { it.priority > 0 && it.power >= 40.0 }
            if (priorityMove != null && priorityMove !in selected) {
                if (selected.size < slots) {
                    selected.add(priorityMove)
                } else {
                    val replaceable = selected
                        .filter { it !== reservedCoverage && it.elementalType !in bossTypes }
                        .minByOrNull { scores.getValue(it) }
                        ?: selected
                            .filter { it !== reservedCoverage }
                            .minByOrNull { scores.getValue(it) }
                    if (replaceable != null) {
                        val priorityScore = scores.getValue(priorityMove)
                        val replaceableScore = scores.getValue(replaceable)
                        if (priorityScore >= replaceableScore * 0.55) {
                            selected.remove(replaceable)
                            selected.add(priorityMove)
                        }
                    }
                }
            }
        }

        return selected.sortedByDescending { scores.getValue(it) }.take(slots)
    }

    private fun coverageAdjustedScore(
        move: MoveTemplate,
        score: Double,
        covered: Set<ElementalType>,
        usedTypes: Set<ElementalType>
    ): Double {
        val targets = ElementalTypes.all()
        val newlyHit = targets.count { target ->
            target !in covered && AIUtility.getDamageMultiplier(move.elementalType, target) > 1.0
        }
        val coverageGain = newlyHit.toDouble() / targets.size.coerceAtLeast(1)
        val duplicate = if (move.elementalType in usedTypes) DUPLICATE_TYPE_FACTOR else 1.0
        return score * (1.0 + COVERAGE_WEIGHT * coverageGain) * duplicate
    }

    private fun superEffectivelyHit(moveTypes: Collection<ElementalType>): Set<ElementalType> =
        ElementalTypes.all().filter { target ->
            moveTypes.any { AIUtility.getDamageMultiplier(it, target) > 1.0 }
        }.toSet()

    // weighted pick over the best few options; low temperature is nearly always the top one
    private fun <T> softmaxPick(options: List<Pair<T, Double>>, temperature: Double, rng: Random): T? {
        val pool = options.sortedByDescending { it.second }.take(SOFTMAX_POOL)
        if (pool.isEmpty()) return null
        val best = pool.first().second
        if (best <= 0.0 || temperature <= 0.0 || pool.size == 1) return pool.first().first

        val weights = pool.map { (_, score) -> exp((score / best - 1.0) / temperature) }
        var roll = rng.nextDouble() * weights.sum()
        for (i in pool.indices) {
            roll -= weights[i]
            if (roll <= 0.0) return pool[i].first
        }
        return pool.last().first
    }

    private fun movesetScore(
        move: MoveTemplate,
        bossTypes: Set<ElementalType>,
        attack: Int,
        specialAttack: Int
    ): Double {
        val bestAttackStat = maxOf(attack, specialAttack).toDouble()
        val usedAttackStat = when (move.damageCategory) {
            DamageCategories.PHYSICAL -> attack.toDouble()
            DamageCategories.SPECIAL -> specialAttack.toDouble()
            else -> bestAttackStat
        }

        val categoryFit = (usedAttackStat / bestAttackStat).coerceIn(0.55, 1.0)
        val accuracy = if (move.accuracy <= 0.0) 1.0 else (move.accuracy / 100.0).coerceIn(0.0, 1.0)
        val stab = if (move.elementalType in bossTypes) 1.25 else 1.0
        val risky = if (move.name in RISKY_SELF_NERF_MOVES) 0.86 else 1.0
        val recoil = if (move.name in RECOIL_MOVES) RECOIL_FACTOR else 1.0
        val delayed = if (move.name in DELAYED_ATTACK_MOVES) 0.60 else 1.0
        val priority = if (move.priority > 0) 1.05 else 1.0

        return move.power * expectedHits(move) * accuracy.pow(0.75) * categoryFit * stab * risky * recoil * delayed * priority
    }

    fun expectedHits(move: MoveTemplate): Double {
        if (move.name == "triplekick" || move.name == "tripleaxel") return 2.4
        if (move.name == "populationbomb") return 7.0
        val range = AIUtility.multiHitMoves[move.name] ?: return 1.0
        return if (range.first == range.second) range.first.toDouble() else 3.0
    }

    // bosses lean toward what their species learns on its own over TM and tutor picks
    private fun sourceBonuses(learnset: Learnset): Map<String, Double> {
        val learners = naturalLearnerCounts()
        val bonuses = HashMap<String, Double>()
        learnset.eggMoves.forEach { bonuses[it.name] = EGG_MOVE_BONUS }
        naturalMoves(learnset).forEach { move ->
            val signature = (learners[move.name] ?: Int.MAX_VALUE) <= SIGNATURE_MAX_LEARNERS
            bonuses[move.name] = if (signature) SIGNATURE_MOVE_BONUS else NATURAL_MOVE_BONUS
        }
        return bonuses
    }

    private fun naturalMoves(learnset: Learnset): Set<MoveTemplate> = buildSet {
        learnset.levelUpMoves.values.forEach(::addAll)
        addAll(learnset.evolutionMoves)
        addAll(learnset.specialMoves)
        addAll(learnset.formChangeMoves)
    }

    private fun naturalLearnerCounts(): Map<String, Int> {
        val allSpecies = PokemonSpecies.species
        naturalLearnerCache?.takeIf { it.first == allSpecies.size }?.let { return it.second }

        val counts = HashMap<String, Int>()
        for (species in allSpecies) {
            val learned = HashSet<String>()
            naturalMoves(species.moves).mapTo(learned) { it.name }
            species.forms.forEach { form -> naturalMoves(form.moves).mapTo(learned) { it.name } }
            learned.forEach { name -> counts[name] = (counts[name] ?: 0) + 1 }
        }
        naturalLearnerCache = allSpecies.size to counts
        return counts
    }

    private fun chooseThreatCoverageMove(
        ranked: List<MoveTemplate>,
        scores: Map<MoveTemplate, Double>,
        severeWeaknesses: List<ElementalType>,
        tier: BossTier
    ): MoveTemplate? {
        if (severeWeaknesses.isEmpty() || ranked.isEmpty()) return null

        val bestScore = scores.getValue(ranked.first())
        val minimumScore = bestScore * coverageQualityThreshold(tier)

        return ranked
            .asSequence()
            .map { move ->
                val score = scores.getValue(move)
                val coverage = severeWeaknesses.maxOfOrNull { threat ->
                    AIUtility.getDamageMultiplier(move.elementalType, threat)
                } ?: 1.0
                Triple(move, score, coverage)
            }
            .filter { (_, score, coverage) -> score >= minimumScore && coverage > 1.0 }
            .maxByOrNull { (_, score, coverage) -> score * coverage }
            ?.first
    }

    private fun severeWeaknesses(bossTypes: Set<ElementalType>): List<ElementalType> =
        ElementalTypes.all().filter { incomingType ->
            bossTypes.fold(1.0) { multiplier, bossType ->
                multiplier * AIUtility.getDamageMultiplier(incomingType, bossType)
            } >= 3.5
        }

    private fun coverageReserveChance(tier: BossTier): Double = when (tier) {
        BossTier.UNCOMMON -> 0.60
        BossTier.RARE -> 0.80
        BossTier.EPIC -> 0.96
        BossTier.LEGENDARY, BossTier.MYTHIC -> 1.0
    }

    private fun coverageQualityThreshold(tier: BossTier): Double = when (tier) {
        BossTier.UNCOMMON -> 0.70
        BossTier.RARE -> 0.60
        BossTier.EPIC -> 0.50
        BossTier.LEGENDARY, BossTier.MYTHIC -> 0.45
    }

    private fun selectionTemperature(tier: BossTier): Double = when (tier) {
        BossTier.UNCOMMON -> 0.35
        BossTier.RARE -> 0.22
        BossTier.EPIC -> 0.12
        BossTier.LEGENDARY, BossTier.MYTHIC -> 0.05
    }

    private fun offensiveStyle(attack: Int, specialAttack: Int): String {
        val stronger = maxOf(attack, specialAttack).toDouble().coerceAtLeast(1.0)
        val gap = kotlin.math.abs(attack - specialAttack) / stronger
        return when {
            gap <= 0.10 -> "MIXED"
            attack > specialAttack -> "PHYSICAL"
            else -> "SPECIAL"
        }
    }

    private fun minimumAccuracyFor(aiSkill: Int): Double = when (aiSkill.coerceIn(0, 5)) {
        0, 1, 2 -> 90.0
        3 -> 85.0
        4 -> 80.0
        else -> 70.0
    }
}
