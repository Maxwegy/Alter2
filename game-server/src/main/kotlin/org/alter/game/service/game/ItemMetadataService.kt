package org.alter.game.service.game

import AnimationData
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.openrune.cache.CacheManager
import dev.openrune.cache.CacheManager.getItem
import dev.openrune.cache.filestore.definition.data.ItemType
import dev.openrune.cache.filestore.definition.data.ParamMapper
import gg.rsmod.util.ServerProperties
import gg.rsmod.util.Stopwatch
import io.github.oshai.kotlinlogging.KotlinLogging
import it.unimi.dsi.fastutil.bytes.Byte2ByteOpenHashMap
import org.alter.game.Server
import org.alter.game.model.World
import org.alter.game.service.Service
import org.yaml.snakeyaml.LoaderOptions
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * @author Tom <rspsmods@gmail.com>
 */
class ItemMetadataService : Service {
    override fun init(
        server: Server,
        world: World,
        serviceProperties: ServerProperties,
    ) {
        loadAll()
    }

    var ms: Long = 0
    fun loadAll() {
        val stopwatch = Stopwatch.createStarted().reset().start()
        val mapper = overrideMapper()

        val path = Paths.get("../data/cfg/items")

        try {
            /**
             * Loads item examine text from an external CSV file and assigns it to item definitions.
             *
             * The file is expected to be located at `../data/cfg/objs.csv` and should contain item IDs
             * paired with their respective examine text, separated by commas.
             *
             * - The first value in each line is treated as the item ID.
             * - The remaining text after the first comma is treated as the examine description.
             * - The examine text is assigned to the corresponding item definition if the ID is valid.
             *
             * This ensures that item examine information gets loaded from an external source at runtime.
             */
            Paths.get("../data/cfg/objs.csv").toFile().forEachLine { line ->
                val parts = line.split(",")
                if (parts.size >= 2) {
                    val id = parts[0].toIntOrNull()
                    val examine = line.substringAfter(',').trim()
                    if (id != null) {
                        getItem(id).examine = examine
                    }
                }
            }

            /**
             * Initializes item definitions by loading cached item configurations and updating specific attributes.
             *
             * - Adjusts item weight by dividing the cached value by 1000.
             * - Sets the attack speed using a validated parameter (ID 14).
             * - Determines the weapon type for equippable items in the weapon slot (equipSlot 3) based on their category.
             * - Assigns the equip type from the item's appearance override.
             * - Populates item bonuses using a predefined set of validated parameters.
             *
             * This process ensures that item attributes are properly loaded and validated from cache for use in gameplay.
             */
            CacheManager.getItems().forEach { (_, item) ->
                val def = getItem(item.id)

                def.weight /= 1000
                def.equipType = def.appearanceOverride1

                def.attackSpeed = def.getValidatedParam(
                    ParamMapper.item.ATTACK_RATE,
                    7
                ) // Just in case the Attack Rate would be not configurated in cache.

                if (def.equipSlot == 3) {
                    def.weaponType = WeaponCategory.get(def, def.category)
                }


                def.bonuses =
                    intArrayOf(
                        def.getValidatedParam(ParamMapper.item.STAB_ATTACK_BONUS),
                        def.getValidatedParam(ParamMapper.item.SLASH_ATTACK_BONUS),
                        def.getValidatedParam(ParamMapper.item.CRUSH_ATTACK_BONUS),
                        def.getValidatedParam(ParamMapper.item.MAGIC_ATTACK_BONUS),
                        def.getValidatedParam(ParamMapper.item.RANGED_ATTACK_BONUS),
                        def.getValidatedParam(ParamMapper.item.STAB_DEFENCE_BONUS),
                        def.getValidatedParam(ParamMapper.item.SLASH_DEFENCE_BONUS),
                        def.getValidatedParam(ParamMapper.item.CRUSH_DEFENCE_BONUS),
                        def.getValidatedParam(ParamMapper.item.MAGIC_DEFENCE_BONUS),
                        def.getValidatedParam(ParamMapper.item.RANGED_DEFENCE_BONUS),
                        def.getValidatedParam(ParamMapper.item.MELEE_STRENGTH),
                        def.getValidatedParam(ParamMapper.item.RANGED_STRENGTH_BONUS),
                        def.getValidatedParam(ParamMapper.item.MAGIC_DAMAGE_STRENGTH) / 10,
                        def.getValidatedParam(ParamMapper.item.PRAYER_BONUS),
                    )

                if (def.params?.containsKey(ParamMapper.item.PRIMARY_SKILL) == true) {
                    def.skillReqs = Byte2ByteOpenHashMap().apply {
                        put(
                            def.getValidatedParam(ParamMapper.item.PRIMARY_SKILL).toByte(),
                            def.getValidatedParam(ParamMapper.item.PRIMARY_LEVEL).toByte()
                        )
                        put(
                            def.getValidatedParam(ParamMapper.item.SECONDARY_SKILL).toByte(),
                            def.getValidatedParam(ParamMapper.item.SECONDARY_LEVEL).toByte()
                        )
                        put(
                            def.getValidatedParam(ParamMapper.item.TERTIARY_SKILL).toByte(),
                            def.getValidatedParam(ParamMapper.item.TERTIARY_LEVEL).toByte()
                        )
                        put(
                            def.getValidatedParam(ParamMapper.item.QUATERNARY_SKILL).toByte(),
                            def.getValidatedParam(ParamMapper.item.QUATERNARY_LEVEL).toByte()
                        )
                    }
                }
            }

            /**
             * Loads and assigns render animations to item definitions from external JSON files.
             *
             * - `bas_mappings.json` maps animation identifiers to their corresponding animation data (e.g., ready, walk, run animations).
             * - `item_bas.json` maps item IDs to the animation identifiers used in the mappings.
             *
             * The process:
             * - Each item ID from `item_bas.json` is matched to its animation data from `bas_mappings.json`.
             * - If a matching animation is found, it populates the item's render animations array with the relevant animation IDs.
             *
             * This ensures that items have appropriate movement and action animations during gameplay.
             */
            val animationMap: Map<String, AnimationData> =
                mapper.readValue(File("../data/cfg/items/renderAnimations/bas_mappings.json").readText())
            val valueMap: Map<Int, Int> = ObjectMapper().apply {
                findAndRegisterModules()
            }.readValue(File("../data/cfg/items/renderAnimations/item_bas.json").readText())
            valueMap.forEach { (item, animMap) ->
                val animation = animationMap[animMap.toString()] ?: return@forEach
                val def = getItem(item)
                def.renderAnimations = intArrayOf(
                    animation.readyAnim,
                    animation.turnAnim,
                    animation.walkAnim,
                    animation.walkAnimBack,
                    animation.walkAnimLeft,
                    animation.walkAnimRight,
                    animation.runAnim,
                )
            }

            /**
             * Loads item override metadata from all files within the "itemOverrides" directory.
             *
             * - The directory is resolved relative to the provided path.
             * - Files are processed in parallel for efficient loading.
             * - Each file is deserialized into a `Metadata` object and passed to the `load` function.
             *
             * This process ensures that custom item attributes or behaviors are loaded at runtime.
             *
             * @TODO Add better context as to why file could not be loaded.
             * @TODO Add support for remaining [`def`] properties override method.
             */
            Files.walk(path.resolve("itemOverrides")).parallel().filter { it.toFile().isFile }.forEach { file ->
                if (file.fileName.toString().contains("FileExample.yml")) return@forEach

                val content = file.toFile().readText()
                content.split(Regex("(?m)^---\\s*$"))
                    .filter { it.isNotBlank() }.forEach { document ->
                        val data = mapper.readValue(document, Metadata::class.java)
                        load(data)
                    }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        ms = stopwatch.elapsed(TimeUnit.MILLISECONDS)
    }

    /** Field names each item override set, by item id (e.g. "attackSpeed", "bonus.3"). Lets later layers respect them. */
    val overriddenFields: Map<Int, Set<String>> get() = overridden

    private val overridden = ConcurrentHashMap<Int, Set<String>>()

    fun load(item: Metadata) {
        overridden[item.id] = applyOverride(getItem(item.id), item)
    }

    /**
     * Applies only the fields [item] actually sets; everything else keeps its cache value. Overrides used to
     * replace the whole definition, so a skillReqs-only file (all 24 Barrows files) zeroed the item's bonuses,
     * attack speed, examine, weight and render animations. Returns the names of the fields it set.
     */
    fun applyOverride(def: ItemType, item: Metadata): Set<String> {
        val applied = linkedSetOf<String>()
        item.name?.let { def.name = it; applied += "name" }
        item.examine?.let { def.examine = it; applied += "examine" }
        item.tradeable?.let { def.isTradeable = it; applied += "tradeable" }
        item.weight?.let { def.weight = it; applied += "weight" }

        val equipment = item.equipment ?: return applied
        val slots = equipment.equipSlot?.let { getEquipmentSlots(it, def.id) }
        if (slots != null) {
            def.equipSlot = slots.slot
            def.equipType = slots.secondary
            applied += "equipSlot"
        }
        equipment.attackSpeed?.let { def.attackSpeed = it; applied += "attackSpeed" }
        val weaponType = equipment.weaponType
        if (weaponType != null) {
            def.weaponType = weaponType
            applied += "weaponType"
        } else if (slots?.slot == 3 && def.weaponType == -1) {
            def.weaponType = 17
            applied += "weaponType"
        }
        equipment.renderAnimations?.let { def.renderAnimations = it.getAsArray(); applied += "renderAnimations" }
        equipment.skillReqs?.let { reqs ->
            val map = Byte2ByteOpenHashMap()
            reqs.filter { it.skill != null && it.level != null }.forEach { req -> map[getSkillId(req.skill!!)] = req.level!!.toByte() }
            def.skillReqs = map
            applied += "skillReqs"
        }

        val overrides = equipment.bonuses()
        if (overrides.any { it != null }) {
            val bonuses = (if (def.hasBonuses()) def.bonuses else IntArray(BONUS_COUNT)).copyOf(BONUS_COUNT)
            overrides.forEachIndexed { index, value ->
                if (value != null) {
                    bonuses[index] = value
                    applied += "bonus.$index"
                }
            }
            def.bonuses = bonuses
        }
        return applied
    }

    private fun getEquipmentSlots(
        slot: String,
        id: Int? = null,
    ): EquipmentSlots {
        val equipSlot: Int
        var equipType = -1
        when (slot) {
            "hat" -> equipSlot = 0
            "cape" -> equipSlot = 1
            "neck" -> equipSlot = 2
            "weapon" -> equipSlot = 3
            "torso" -> equipSlot = 4
            "shield" -> equipSlot = 5
            "legs" -> equipSlot = 7
            "hands" -> equipSlot = 9
            "feet" -> equipSlot = 10
            "ring" -> equipSlot = 12
            "ammo" -> equipSlot = 13

            "head" -> {
                equipSlot = 0
                equipType = 8
            }
            // For hats that requires hair removal
            "nohair" -> {
                equipSlot = 0
                equipType = 11
            }

            "2h" -> {
                equipSlot = 3
                equipType = 5
            }

            "body" -> {
                equipSlot = 4
                equipType = 6
            }

            else -> throw IllegalArgumentException("Illegal equipment slot: $slot, $id")
        }
        return EquipmentSlots(equipSlot, equipType)
    }

    private data class EquipmentSlots(val slot: Int, val secondary: Int)


    private fun ItemType.getValidatedParam(key: Int, defaultValue: Int = 0): Int {
        if (this.params?.get(key) != null) {
            try {
                return this.params?.get(key) as Int
            } catch (e: Exception) {
                println("${this.id} || ${this.params}")
                e.printStackTrace()
            }
        }

        /**
         * @TODO Rethink the logic, gets printed out even for items that are not wearable.
         * logger.warn {
         *   "Item with ID: ${this.id} is missing the key '$key' in its params. Full params list: ${this.params}. Default value was set: $defaultValue."
         * }
         */
        return defaultValue
    }

    private fun getSkillId(name: String): Byte =
        when (name) {
            // Need to get a better dump db. As we can see, this one has some
            // inconsistency for some reason.
            "attack" -> 0
            "defence" -> 1
            "strength" -> 2
            "hitpoints" -> 3
            "range", "ranged" -> 4
            "prayer" -> 5
            "magic" -> 6
            "cooking" -> 7
            "woodcutting" -> 8
            "fletching" -> 9
            "fishing" -> 10
            "firemaking" -> 11
            "crafting" -> 12
            "smithing" -> 13
            "mining" -> 14
            "herblore" -> 15
            "agility" -> 16
            "thieving", "theiving" -> 17
            "slayer" -> 18
            "farming" -> 19
            "runecrafting", "runecraft" -> 20
            "hunter" -> 21
            "construction", "contruction" -> 22
            "combat" -> 3
            else -> throw IllegalArgumentException("Illegal skill name: $name")
        }

    /** An item override document. Every field is optional; absent fields keep the cache value. */
    data class Metadata(
        var id: Int = -1,
        var name: String? = null,
        var examine: String? = null,
        var tradeable: Boolean? = null,
        var weight: Double? = null,
        @field:JsonAlias("tradeableOnGe") var tradeable_on_ge: Boolean? = null,
        var cost: Int? = null,
        var lowalch: Int? = null,
        var highalch: Int? = null,
        @field:JsonAlias("buyLimit") var buy_limit: Int? = null,
        var equipment: Equipment? = null,
    )

    /** Equipment fields accept both camelCase (the committed files) and snake_case keys. */
    data class Equipment(
        @field:JsonAlias("equip_slot") var equipSlot: String? = null,
        @field:JsonAlias("equip_sound") var equipSound: Int? = null,
        @field:JsonAlias("weapon_type") var weaponType: Int? = null,
        @field:JsonAlias("attack_speed") var attackSpeed: Int? = null,
        @field:JsonAlias("attack_stab") var attackStab: Int? = null,
        @field:JsonAlias("attack_slash") var attackSlash: Int? = null,
        @field:JsonAlias("attack_crush") var attackCrush: Int? = null,
        @field:JsonAlias("attack_magic") var attackMagic: Int? = null,
        @field:JsonAlias("attack_ranged") var attackRanged: Int? = null,
        @field:JsonAlias("defence_stab") var defenceStab: Int? = null,
        @field:JsonAlias("defence_slash") var defenceSlash: Int? = null,
        @field:JsonAlias("defence_crush") var defenceCrush: Int? = null,
        @field:JsonAlias("defence_magic") var defenceMagic: Int? = null,
        @field:JsonAlias("defence_ranged") var defenceRanged: Int? = null,
        @field:JsonAlias("melee_strength") var meleeStrength: Int? = null,
        @field:JsonAlias("ranged_strength") var rangedStrength: Int? = null,
        @field:JsonAlias("magic_damage") var magicDamage: Int? = null,
        var prayer: Int? = null,
        @field:JsonAlias("render_animations") var renderAnimations: RenderAnimations? = null,
        var attackSounds: List<Int>? = null,
        @field:JsonAlias("skill_reqs") var skillReqs: List<SkillRequirement>? = null,
    ) {
        /** In ItemType.bonuses order; null = not overridden. */
        fun bonuses(): List<Int?> = listOf(
            attackStab, attackSlash, attackCrush, attackMagic, attackRanged,
            defenceStab, defenceSlash, defenceCrush, defenceMagic, defenceRanged,
            meleeStrength, rangedStrength, magicDamage, prayer,
        )
    }

    data class RenderAnimations(
        @JsonProperty("standAnimId") val standAnimId: Int = 0,
        @JsonProperty("turnOnSpotAnim") val turnOnSpotAnim: Int = 0,
        @JsonProperty("walkForwardAnimId") val walkForwardAnimId: Int = 0,
        @JsonProperty("walkBackwardsAnimId") val walkBackwardsAnimId: Int = 0,
        @JsonProperty("walkLeftAnimId") val walkLeftAnimId: Int = 0,
        @JsonProperty("walkRightAnimId") val walkRightAnimId: Int = 0,
        @JsonProperty("runAnimId") val runAnimId: Int = 0,
    ) {
        fun getAsArray(): IntArray {
            return listOf(
                standAnimId,
                turnOnSpotAnim,
                walkForwardAnimId,
                walkBackwardsAnimId,
                walkLeftAnimId,
                walkRightAnimId,
                runAnimId
            ).toIntArray()
        }
    }

    data class SkillRequirement(
        var skill: String? = null,
        var level: Int? = null,
    )

    companion object {
        val logger = KotlinLogging.logger {}

        /** ItemType.bonuses length: 10 attack/defence, melee strength, ranged strength, magic damage, prayer. */
        const val BONUS_COUNT = 14

        /** The mapper for override YAML. No Kotlin module: the models are plain mutable beans. */
        fun overrideMapper(): YAMLMapper {
            val loaderOptions = LoaderOptions()
            loaderOptions.codePointLimit = 10 * 1024 * 1024 // 10 MB
            return YAMLMapper(YAMLFactory.builder().loaderOptions(loaderOptions).build())
        }

        /** True when the lateinit [ItemType.bonuses] has been assigned. */
        private fun ItemType.hasBonuses(): Boolean = try {
            bonuses
            true
        } catch (e: UninitializedPropertyAccessException) {
            false
        }
    }
}
