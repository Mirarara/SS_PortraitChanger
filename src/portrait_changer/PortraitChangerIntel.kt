package portrait_changer

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CommDirectoryEntryAPI.EntryType
import com.fs.starfarer.api.campaign.FactionAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.characters.FullName.Gender
import com.fs.starfarer.api.characters.PersonAPI
import com.fs.starfarer.api.impl.campaign.ids.People
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.SectorMapAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipCreator
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipLocation
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.api.util.WeightedRandomPicker
import exerelin.campaign.intel.agents.AgentIntel
import java.awt.Color
import java.util.LinkedHashSet
import lunalib.lunaExtensions.addLunaElement
import lunalib.lunaExtensions.addLunaSpriteElement
import lunalib.lunaExtensions.addLunaTextfield
import lunalib.lunaSettings.LunaSettings
import lunalib.lunaUI.elements.LunaElement
import lunalib.lunaUI.elements.LunaSpriteElement

class PortraitChangerIntel : BaseIntelPlugin() {

    @Transient
    var panel: CustomPanelAPI? = null

    @Transient
    var element: TooltipMakerAPI? = null

    @Transient
    private var portraitPool: Set<String> = emptySet()

    @Transient
    private var marketPeople: Set<PersonAPI> = emptySet()

    private var gender = PCLunaSettingListener.defaultGender
    private var selectedPerson: PersonAPI? = null
    private var lastScroller = 0f
    private var marketName = ""

    private val isNexerelin = Global.getSettings().modManager.isModEnabled("nexerelin")

    private fun importantPeopleIds() = listOf(
        People.DAUD,
        People.ANDRADA,
        People.MACARIO,
        People.COTTON,
        People.VIRENS,
        People.SEDGE,
        People.BORNANEW,
        People.JASPIS,
        People.KANTA,
        People.CYDONIA,
        People.SUN,
        People.ARROYO,
        People.BAIRD,
        People.SEBESTYEN,
        People.COUREUSE,
        People.GARGOYLE,
        People.IBRAHIM,
        People.HORUS_YARIBAY,
        "reynard_hannan",
        "nex_dissonant",
    )

    override fun getName() = "Change Portrait"

    override fun hasLargeDescription() = true

    override fun createLargeDescription(panel: CustomPanelAPI?, width: Float, height: Float) {
        this.panel = panel
        recreatePanel()
    }

    private fun currentMarket(): MarketAPI? {
        Global.getSector().campaignUI.currentInteractionDialog
            ?.interactionTarget
            ?.market
            ?.let { return it }

        if (!PCLunaSettingListener.allowPlayerMarketCommDirectory) return null
        val id = Global.getSector().memoryWithoutUpdate.getString(PCModPlugin.memID_selectedPlayerMarket)
        return id?.takeIf(String::isNotBlank)?.let { Global.getSector().economy.getMarket(it) }
    }

    private fun peopleAtCurrentMarket(): List<PersonAPI> {
        val market = currentMarket() ?: run {
            marketName = ""
            marketPeople = emptySet()
            return emptyList()
        }

        marketName = market.name
        val people = LinkedHashSet<PersonAPI>()
        people.addAll(market.peopleCopy)
        market.admin?.let(people::add)
        market.commDirectory.entriesCopy
            .asSequence()
            .filter { it.type == EntryType.PERSON }
            .mapNotNull { it.entryData as? PersonAPI }
            .forEach(people::add)
        marketPeople = people
        return people.toList()
    }

    private fun aiAdministrators(): List<PersonAPI> = Misc.getPlayerMarkets(false)
        .mapNotNull { market -> market.admin?.takeIf { it.aiCoreId != null } }

    private fun importantPeople(): List<PersonAPI> {
        val registry = Global.getSector().importantPeople
        return importantPeopleIds().mapNotNull(registry::getPerson)
    }

    private fun eligiblePeople(): List<PersonAPI> {
        val people = LinkedHashSet<PersonAPI>()
        if (PCLunaSettingListener.allowPlayer) people += Global.getSector().playerPerson
        if (PCLunaSettingListener.allowOfficer) {
            people += Global.getSector().playerFleet.fleetData.officersCopy.map { it.person }
        }
        if (PCLunaSettingListener.allowAdmin) {
            people += Global.getSector().characterData.admins.map { it.person }
        }
        if (isNexerelin && PCLunaSettingListener.allowNexAgent) {
            people += Global.getSector().intelManager.intel.filterIsInstance<AgentIntel>().map { it.agent }
        }
        if (PCLunaSettingListener.allowAIAdmin) people += aiAdministrators()
        if (PCLunaSettingListener.allowImportantPeople) people += importantPeople()
        if (LunaSettings.getBoolean("portrait_changer", "portraitChanger_currentMarketNpc") != false) {
            people += peopleAtCurrentMarket()
        } else {
            marketName = ""
            marketPeople = emptySet()
        }
        return people.toList()
    }

    private fun recreatePanel() {
        val panel = panel ?: return
        element?.let { old ->
            old.externalScroller?.let {
                old.removeComponent(it)
                panel.removeComponent(it)
            }
            panel.removeComponent(old)
        }

        val ui = panel.createUIElement(panel.position.width, panel.position.height, true)
        element = ui
        var y = ui.addSectionHeading("Select Character", Alignment.MID, 0f).position.height + 20f

        val people = eligiblePeople()
        if (selectedPerson !in people) selectedPerson = people.firstOrNull()
        y = addCharacterSelector(people, y)
        ui.addSpacer(5f)

        if (people.isEmpty()) {
            ui.addPara("No eligible characters are available.", 0f).position.inTL(6f, y)
            panel.addUIElement(ui)
            return
        }

        val portraitHeader = ui.addSectionHeading("Select Portrait", Alignment.MID, 0f).position.inTL(0f, y)
        ui.addSpacer(portraitHeader.height + 10f)
        y += portraitHeader.height + 10f

        var x = 5f
        val genderButton = ui.addLunaElement(180f, 50f).apply {
            enableTransparency = true
            backgroundAlpha = 0.5f
            backgroundColor = when (gender) {
                Gender.FEMALE -> Color.PINK
                Gender.MALE -> Color.CYAN
                else -> Misc.getDarkPlayerColor()
            }
            addText("Portrait Gender: ${genderLabel()}", Misc.getBasePlayerColor())
            centerText()
            addTooltip(
                "Filters the portrait pool by gender. Click to switch gender.",
                100f,
                TooltipLocation.BELOW,
            )
            onClick {
                rememberScroll()
                gender = if (gender == Gender.FEMALE) Gender.MALE else Gender.FEMALE
                recreatePanel()
            }
        }
        genderButton.position.inTL(x, y)
        x += genderButton.width + 10f

        val randomPortraitButton = ui.addLunaElement(150f, 50f).apply {
            enableTransparency = true
            backgroundColor = Color.GREEN
            backgroundAlpha = 0.7f
            addText("Random Portrait", Color.ORANGE)
            centerText()
            addTooltip(
                "Picks a random portrait from the selected gender pool - perfect for the lazy.",
                100f,
                TooltipLocation.BELOW,
            )
            onHoverEnter { backgroundAlpha = 0.4f }
            onHoverExit { backgroundAlpha = 0.7f }
            onClick {
                val person = selectedPerson
                if (person != null && portraitPool.isNotEmpty()) {
                    val picker = WeightedRandomPicker<String>()
                    picker.addAll(portraitPool)
                    setPortrait(person, picker.pick())
                    recreatePanel()
                }
            }
        }
        randomPortraitButton.position.inTL(x, y)
        x += randomPortraitButton.width + 10f

        val applyGenderButton = ui.addLunaElement(120f, 50f).apply {
            enableTransparency = true
            backgroundColor = Color.ORANGE
            backgroundAlpha = 0.7f
            addText("Apply Gender", Color.CYAN)
            centerText()
            addTooltip(
                "Changes the selected character's gender without changing their portrait or name.",
                160f,
                TooltipLocation.BELOW,
            )
            onHoverEnter { backgroundAlpha = 0.4f }
            onHoverExit { backgroundAlpha = 0.7f }
            onClick {
                selectedPerson?.let { person ->
                    person.gender = gender
                    person.name.gender = gender
                    playClickSound()
                    recreatePanel()
                }
            }
        }
        applyGenderButton.position.inTL(x, y)
        x += applyGenderButton.width + 10f

        val separator = ui.addLunaElement(5f, 50f).apply {
            backgroundColor = Misc.getBasePlayerColor()
        }
        separator.position.inTL(x, y)
        x += separator.width + 10f

        val randomNameButton = ui.addLunaElement(120f, 50f).apply {
            enableTransparency = true
            backgroundColor = Color.ORANGE
            backgroundAlpha = 0.7f
            addText("Random Name", Color.CYAN)
            centerText()
            addTooltip(
                "Chooses a random name from {\"optional_name_pool.txt\"} in the mod folder, or uses one generated by the game.",
                100f,
                TooltipLocation.BELOW,
                "\"optional_name_pool.txt\"",
            )
            onHoverEnter { backgroundAlpha = 0.4f }
            onHoverExit { backgroundAlpha = 0.7f }
        }
        randomNameButton.position.inTL(x, y)
        x += randomNameButton.width + 10f

        val nameInput = ui.addLunaTextfield(selectedPerson?.nameString ?: "", false, 250f, 50f)
        nameInput.position.inTL(x, y)
        randomNameButton.onClick { nameInput.changePara(pickRandomName()) }
        x += nameInput.width + 10f

        val applyNameButton = ui.addLunaElement(120f, 50f).apply {
            enableTransparency = true
            backgroundColor = Color.ORANGE
            backgroundAlpha = 0.7f
            addText("Apply Name", Color.CYAN)
            centerText()
            addTooltip(
                "Sets the selected character's name to the text in the input box.",
                100f,
                TooltipLocation.BELOW,
            )
            onHoverEnter { backgroundAlpha = 0.4f }
            onHoverExit { backgroundAlpha = 0.7f }
            onClick {
                selectedPerson?.name?.apply {
                    first = nameInput.getText()
                    last = ""
                }
                playClickSound()
            }
        }
        applyNameButton.position.inTL(x, y)

        ui.addSpacer(genderButton.height + 10f)
        y += genderButton.height + 10f
        ui.addPara("", 0f).position.inTL(10f, 240f)

        portraitPool = buildPortraitPool()
        var count = 1
        var column = 0
        var previous: LunaElement? = null
        for (portrait in portraitPool) {
            val sprite = ui.addLunaSpriteElement(
                portrait,
                LunaSpriteElement.ScalingTypes.STRETCH_SPRITE,
                96f,
                0f,
            ).apply {
                renderBorder = true
                enableTransparency = true
                borderAlpha = 0.8f
                getSprite().alphaMult = 0.6f
                onHoverEnter {
                    getSprite().alphaMult = 1f
                    borderAlpha = 1f
                    playScrollSound()
                }
                onHoverExit {
                    getSprite().alphaMult = 0.8f
                    borderAlpha = 0.8f
                }
                onClick {
                    rememberScroll()
                    selectedPerson?.let { setPortrait(it, portrait) }
                    playClickSound()
                    recreatePanel()
                }
            }

            if (PCLunaSettingListener.showCountNum) {
                ui.setParaFont("graphics/fonts/orbitron20aabold.fnt")
                ui.addPara("$count.", 0f)
            }
            sprite.position.setSize(96f, 96f)
            sprite.getSprite().setSize(96f, 96f)
            if (column == 0) {
                sprite.position.inTL(6f, y)
                y += 96f + if (PCLunaSettingListener.showCountNum) 40f else 0f
            } else {
                sprite.position.rightOfMid(previous!!.elementPanel, 10f)
            }
            previous = sprite
            count++
            column = (column + 1) % PCLunaSettingListener.imageNumInOneRow
        }

        ui.addSpacer((ui.heightSoFar - y) * -1f)
        panel.addUIElement(ui)
        ui.externalScroller.yOffset = lastScroller
    }

    private fun buildPortraitPool(): Set<String> {
        val portraits = LinkedHashSet<String>()
        portraits += Global.getSector().playerFaction.getPortraits(gender).items
        if (PCLunaSettingListener.allFactions) {
            Global.getSector().allFactions.forEach { faction: FactionAPI ->
                portraits += faction.getPortraits(gender).items
            }
        }
        return portraits
    }

    private fun addCharacterSelector(characters: List<PersonAPI>, initialY: Float): Float {
        val officers = Global.getSector().playerFleet.fleetData.officersCopy.map { it.person }.toSet()
        val administrators = (
            Global.getSector().characterData.admins.map { it.person } + aiAdministrators()
        ).toSet()
        val agents = if (isNexerelin) {
            Global.getSector().intelManager.intel.filterIsInstance<AgentIntel>().map { it.agent }.toSet()
        } else {
            emptySet()
        }
        val important = importantPeople().toSet()

        var y = initialY
        var column = 0
        var previous: LunaElement? = null
        for (character in characters) {
            val type = when (character) {
                Global.getSector().playerPerson -> "Player"
                in officers -> "Officer"
                in administrators -> "Admin"
                in agents -> "Agent"
                in important -> "Important Person"
                in marketPeople -> "Current Market NPC"
                else -> "Character"
            }
            val color = when (character) {
                in officers -> Misc.getBrightPlayerColor().darker().darker()
                in administrators -> Misc.getHighlightColor().darker().darker()
                in marketPeople -> Misc.getPositiveHighlightColor().darker()
                else -> Misc.getDarkPlayerColor()
            }

            val sprite = element!!.addLunaSpriteElement(
                character.portraitSprite,
                LunaSpriteElement.ScalingTypes.STRETCH_SPRITE,
                64f,
                0f,
            ).apply {
                renderBorder = true
                enableTransparency = true
                borderColor = color
                borderAlpha = 0.8f
                getSprite().alphaMult = if (character == selectedPerson) 1f else 0.4f
                onHoverEnter {
                    borderAlpha = 1f
                    playScrollSound()
                }
                onHoverExit { borderAlpha = 0.8f }
                onClick {
                    rememberScroll()
                    selectedPerson = character
                    getSprite().alphaMult = 1f
                    playClickSound()
                    recreatePanel()
                }
            }

            element!!.addTooltipToPrevious(object : TooltipCreator {
                override fun isTooltipExpandable(tooltipParam: Any?) = false

                override fun getTooltipWidth(tooltipParam: Any?) = 350f

                override fun createTooltip(tooltip: TooltipMakerAPI?, expanded: Boolean, tooltipParam: Any?) {
                    val tip = tooltip ?: return
                    tip.addSectionHeading(type, Misc.getTextColor(), color, Alignment.MID, 0f)
                    tip.addImage(character.portraitSprite, 128f, 0f)
                    tip.addSpacer(3f)
                    tip.addPara(
                        "Name: ${character.name.fullName}",
                        0f,
                        Misc.getTextColor(),
                        Misc.getHighlightColor(),
                        "Name: ",
                    )
                    tip.addPara(
                        "Gender: ${genderLabel(character.gender)}",
                        5f,
                        Misc.getTextColor(),
                        Misc.getHighlightColor(),
                        "Gender: ",
                    )
                    tip.addPara(
                        "Sprite: ${character.portraitSprite}",
                        5f,
                        Misc.getTextColor(),
                        Misc.getHighlightColor(),
                        "Sprite:",
                    )
                    if (character in agents) {
                        val agent = Global.getSector().intelManager.intel
                            .filterIsInstance<AgentIntel>()
                            .firstOrNull { it.agent == character }
                        agent?.let {
                            tip.addPara(
                                "Skills: ${Misc.getAndJoined(it.specializationNames)}",
                                5f,
                                Misc.getTextColor(),
                                Misc.getHighlightColor(),
                                "Skills:",
                            )
                        }
                    }
                    if (character in administrators) {
                        Misc.getPlayerMarkets(false)
                            .firstOrNull { it.admin == character }
                            ?.let {
                                tip.addPara(
                                    "Administers: ${it.name}",
                                    5f,
                                    Misc.getTextColor(),
                                    Misc.getHighlightColor(),
                                    "Administers:",
                                )
                            }
                    }
                    if (character.post != null) {
                        tip.addPara(
                            "Post: ${character.post}",
                            5f,
                            Misc.getTextColor(),
                            Misc.getHighlightColor(),
                            "Post:",
                        )
                    }
                    if (!character.faction.isPlayerFaction) {
                        tip.addPara(
                            "Faction: ${character.faction.displayName}",
                            5f,
                            character.faction.color,
                            Misc.getHighlightColor(),
                            "Faction:",
                        )
                    }
                    if (character in marketPeople && marketName.isNotBlank()) {
                        tip.addPara(
                            "Market: $marketName",
                            5f,
                            Misc.getBasePlayerColor(),
                            Misc.getHighlightColor(),
                            "Market:",
                        )
                    }
                }
            }, TooltipLocation.BELOW)

            sprite.position.setSize(64f, 64f)
            sprite.getSprite().setSize(64f, 64f)
            if (column == 0) {
                sprite.position.inTL(6f, y)
                y += 84f
            } else {
                sprite.position.rightOfMid(previous!!.elementPanel, 40f)
            }
            previous = sprite
            column = (column + 1) % PCLunaSettingListener.imageNumInOneRowSelector
        }
        return y
    }

    private fun setPortrait(person: PersonAPI, portrait: String) {
        if (person == Global.getSector().playerPerson) {
            Global.getSector().characterData.setPortraitName(portrait)
        } else {
            person.portraitSprite = portrait
        }
    }

    private fun pickRandomName(): String {
        return try {
            val names = Global.getSettings()
                .loadText("./optional_name_pool.txt", "portrait_changer")
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()
            names.random()
        } catch (_: Exception) {
            Global.getSector().playerFaction.createRandomPerson(gender).name.fullName
        }
    }

    private fun rememberScroll() {
        lastScroller = element?.externalScroller?.yOffset ?: 0f
    }

    private fun genderLabel(value: Gender = gender) = when (value) {
        Gender.FEMALE -> "Female"
        Gender.MALE -> "Male"
        else -> "Any"
    }

    override fun getIcon(): String {
        Global.getSettings().loadTexture(ICON_PATH)
        return ICON_PATH
    }

    override fun getIntelTags(map: SectorMapAPI?) = mutableSetOf("Personal")

    companion object {
        private const val ICON_PATH = "graphics/icons/intel/portrait_changer.png"
    }
}
