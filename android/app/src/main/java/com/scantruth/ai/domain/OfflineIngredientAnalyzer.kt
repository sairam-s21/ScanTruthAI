package com.scantruth.ai.domain

import com.scantruth.ai.AppPreferences
import com.scantruth.ai.model.AiEngine
import com.scantruth.ai.model.FactorDirection
import com.scantruth.ai.model.HealthFactor
import com.scantruth.ai.model.IngredientAnalysis
import com.scantruth.ai.model.IngredientVerdict
import com.scantruth.ai.model.MedicalProfile
import com.scantruth.ai.model.VerdictLevel

/**
 * Independent offline ingredient analyzer that operates strictly on-device without internet,
 * Gemini Flash, Gemini Nano, or Google AICore.
 *
 * Uses parsed OCR ingredients, OCR allergen declarations, a local ingredient knowledge base,
 * health-profile matching, and deterministic safety rules. Any unrecognized ingredient is
 * explicitly marked [VerdictLevel.UNKNOWN] and is never assumed safe.
 */
object OfflineIngredientAnalyzer {

    private enum class ProfileTag {
        PEANUT,
        TREE_NUT,
        DAIRY,
        LACTOSE,
        GLUTEN,
        SOY,
        EGG,
        FISH,
        SHELLFISH,
        SESAME,
        MUSTARD,
        SULFITE,
        PENICILLIN,
        NSAID,
        DIABETES_SUGAR,
        HYPERTENSION_SODIUM,
        HEART_CHOLESTEROL,
        CAFFEINE_SENSITIVITY,
        VEGAN,
        VEGETARIAN,
        HALAL_PORK,
        PKU
    }

    private data class IngredientRule(
        val keywords: List<String>,
        val baseVerdict: VerdictLevel,
        val summary: String,
        val allergenTags: Set<ProfileTag> = emptySet(),
        val conditionCautionTags: Set<ProfileTag> = emptySet(),
        val dietaryConflictTags: Set<ProfileTag> = emptySet(),
        val defaultCategoryHint: String? = null
    )

    private val database: List<IngredientRule> = listOf(
        // Major allergens
        IngredientRule(
            keywords = listOf("peanut", "groundnut", "arachis"),
            baseVerdict = VerdictLevel.SAFE,
            summary = "Peanut-derived ingredient; major allergen.",
            allergenTags = setOf(ProfileTag.PEANUT)
        ),
        IngredientRule(
            keywords = listOf(
                "almond", "cashew", "walnut", "hazelnut", "pistachio",
                "pecan", "macadamia", "brazil nut", "pine nut", "tree nut"
            ),
            baseVerdict = VerdictLevel.SAFE,
            summary = "Tree-nut ingredient; major allergen.",
            allergenTags = setOf(ProfileTag.TREE_NUT)
        ),
        IngredientRule(
            keywords = listOf(
                "milk", "whey", "casein", "caseinate", "lactose", "butter",
                "cream", "ghee", "cheese", "buttermilk", "yogurt", "yoghurt", "milk solids"
            ),
            baseVerdict = VerdictLevel.SAFE,
            summary = "Dairy-derived ingredient containing milk proteins or lactose.",
            allergenTags = setOf(ProfileTag.DAIRY, ProfileTag.LACTOSE),
            dietaryConflictTags = setOf(ProfileTag.VEGAN)
        ),
        IngredientRule(
            keywords = listOf(
                "wheat", "maida", "atta", "semolina", "suji", "barley",
                "rye", "malt", "gluten", "spelt", "triticale"
            ),
            baseVerdict = VerdictLevel.SAFE,
            summary = "Gluten-containing grain ingredient.",
            allergenTags = setOf(ProfileTag.GLUTEN),
            conditionCautionTags = setOf(ProfileTag.DIABETES_SUGAR)
        ),
        IngredientRule(
            keywords = listOf("oat", "oats", "rolled oats", "oat flour"),
            baseVerdict = VerdictLevel.SAFE,
            summary = "Whole grain oat ingredient; may carry gluten cross-contact unless certified gluten-free.",
            conditionCautionTags = setOf(ProfileTag.GLUTEN)
        ),
        IngredientRule(
            keywords = listOf("soy", "soya", "soybean", "soy lecithin", "soya lecithin", "tofu", "edamame"),
            baseVerdict = VerdictLevel.SAFE,
            summary = "Soy-derived ingredient.",
            allergenTags = setOf(ProfileTag.SOY)
        ),
        IngredientRule(
            keywords = listOf("egg", "albumen", "albumin", "egg yolk", "egg white", "mayonnaise"),
            baseVerdict = VerdictLevel.SAFE,
            summary = "Egg-derived ingredient.",
            allergenTags = setOf(ProfileTag.EGG),
            dietaryConflictTags = setOf(ProfileTag.VEGAN, ProfileTag.VEGETARIAN)
        ),
        IngredientRule(
            keywords = listOf("shrimp", "prawn", "crab", "lobster", "shellfish", "crustacean", "mollusc", "oyster"),
            baseVerdict = VerdictLevel.SAFE,
            summary = "Shellfish ingredient; major allergen.",
            allergenTags = setOf(ProfileTag.SHELLFISH),
            dietaryConflictTags = setOf(ProfileTag.VEGAN, ProfileTag.VEGETARIAN)
        ),
        IngredientRule(
            keywords = listOf("fish", "salmon", "tuna", "cod", "anchovy", "fish oil", "omega-3 fish"),
            baseVerdict = VerdictLevel.SAFE,
            summary = "Fish-derived ingredient.",
            allergenTags = setOf(ProfileTag.FISH),
            dietaryConflictTags = setOf(ProfileTag.VEGAN, ProfileTag.VEGETARIAN)
        ),
        IngredientRule(
            keywords = listOf("sesame", "tahini", "til"),
            baseVerdict = VerdictLevel.SAFE,
            summary = "Sesame seed ingredient.",
            allergenTags = setOf(ProfileTag.SESAME)
        ),
        IngredientRule(
            keywords = listOf("mustard", "rai"),
            baseVerdict = VerdictLevel.SAFE,
            summary = "Mustard seed ingredient.",
            allergenTags = setOf(ProfileTag.MUSTARD)
        ),
        IngredientRule(
            keywords = listOf("sulfite", "sulphite", "metabisulfite", "metabisulphite", "sulphur dioxide", "sulfur dioxide", "e220", "e221", "e222", "e223", "e224", "e228"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "Sulfite preservative that may trigger asthma or sulfite sensitivity.",
            allergenTags = setOf(ProfileTag.SULFITE)
        ),

        // Medicine allergens & common actives
        IngredientRule(
            keywords = listOf("penicillin", "amoxicillin", "ampicillin", "piperacillin"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "Beta-lactam antibiotic; avoid if allergic to penicillin.",
            allergenTags = setOf(ProfileTag.PENICILLIN),
            defaultCategoryHint = "medicine"
        ),
        IngredientRule(
            keywords = listOf("ibuprofen", "aspirin", "naproxen", "diclofenac", "aceclofenac"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "NSAID active ingredient; may affect asthma, stomach, or kidney conditions.",
            allergenTags = setOf(ProfileTag.NSAID),
            defaultCategoryHint = "medicine"
        ),
        IngredientRule(
            keywords = listOf("paracetamol", "acetaminophen"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "Analgesic/antipyretic active ingredient; follow exact daily dose limits.",
            defaultCategoryHint = "medicine"
        ),

        // Dietary-only animal sources
        IngredientRule(
            keywords = listOf("honey"),
            baseVerdict = VerdictLevel.SAFE,
            summary = "Natural sweetener; adds free sugars and is non-vegan.",
            conditionCautionTags = setOf(ProfileTag.DIABETES_SUGAR),
            dietaryConflictTags = setOf(ProfileTag.VEGAN)
        ),
        IngredientRule(
            keywords = listOf("gelatin", "gelatine"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "Animal-derived gelling agent (source unspecified: beef/pork/fish).",
            dietaryConflictTags = setOf(ProfileTag.VEGAN, ProfileTag.VEGETARIAN, ProfileTag.HALAL_PORK)
        ),
        IngredientRule(
            keywords = listOf("lard", "pork", "bacon", "ham"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "Pork-derived ingredient.",
            dietaryConflictTags = setOf(ProfileTag.VEGAN, ProfileTag.VEGETARIAN, ProfileTag.HALAL_PORK)
        ),
        IngredientRule(
            keywords = listOf("carmine", "cochineal", "e120"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "Insect-derived red food colouring.",
            dietaryConflictTags = setOf(ProfileTag.VEGAN, ProfileTag.VEGETARIAN)
        ),

        // Sugars / High glycemic ingredients
        IngredientRule(
            keywords = listOf(
                "sugar", "sucrose", "cane sugar", "brown sugar", "invert sugar",
                "liquid glucose", "glucose syrup", "corn syrup", "high fructose corn syrup",
                "dextrose", "fructose", "maltose", "jaggery", "dates syrup", "date syrup",
                "maltodextrin", "caramel", "e150", "e150d"
            ),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "Added sugar or rapidly absorbed carbohydrate; limit for blood sugar control.",
            conditionCautionTags = setOf(ProfileTag.DIABETES_SUGAR)
        ),

        // Sweeteners & sugar alcohols
        IngredientRule(
            keywords = listOf("aspartame", "e951"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "Artificial sweetener containing phenylalanine.",
            allergenTags = setOf(ProfileTag.PKU)
        ),
        IngredientRule(
            keywords = listOf("sucralose", "e955", "acesulfame", "e950", "saccharin", "e954", "sorbitol", "e420", "xylitol", "e967", "mannitol", "e421"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "Non-sugar sweetener or sugar alcohol; excessive intake may cause digestive discomfort."
        ),

        // Sodium / Salt / Raising agents
        IngredientRule(
            keywords = listOf("salt", "edible common salt", "iodised salt", "iodized salt", "sea salt", "sodium chloride"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "Sodium source; monitor intake if managing blood pressure or sodium restriction.",
            conditionCautionTags = setOf(ProfileTag.HYPERTENSION_SODIUM)
        ),
        IngredientRule(
            keywords = listOf("monosodium glutamate", "msg", "e621", "sodium benzoate", "e211", "sodium nitrite", "e250", "sodium nitrate", "e251"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "Sodium-containing flavour enhancer or preservative.",
            conditionCautionTags = setOf(ProfileTag.HYPERTENSION_SODIUM)
        ),
        IngredientRule(
            keywords = listOf("raising agent", "sodium bicarbonate", "baking soda", "500(ii)", "e500", "503(ii)", "e503", "ammonium bicarbonate"),
            baseVerdict = VerdictLevel.SAFE,
            summary = "Common baking leavening agent; contains minor sodium compounds.",
            conditionCautionTags = setOf(ProfileTag.HYPERTENSION_SODIUM)
        ),

        // Fats & oils
        IngredientRule(
            keywords = listOf("hydrogenated", "partially hydrogenated", "vanaspati", "shortening"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "Processed fat that may contain trans or high saturated fats; heart/cholesterol concern.",
            conditionCautionTags = setOf(ProfileTag.HEART_CHOLESTEROL)
        ),
        IngredientRule(
            keywords = listOf("palm oil", "palmolein", "palm kernel oil"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "High in saturated fat; limit for cardiovascular or cholesterol management.",
            conditionCautionTags = setOf(ProfileTag.HEART_CHOLESTEROL)
        ),
        IngredientRule(
            keywords = listOf("edible vegetable oil", "vegetable oil", "vegetable fat", "edible vegetable fat"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "Unspecified vegetable oil/fat source (could include palm or soy oil).",
            conditionCautionTags = setOf(ProfileTag.HEART_CHOLESTEROL, ProfileTag.SOY)
        ),
        IngredientRule(
            keywords = listOf("sunflower oil", "olive oil", "canola oil", "rapeseed oil", "rice bran oil", "corn oil", "safflower oil"),
            baseVerdict = VerdictLevel.SAFE,
            summary = "Plant-based unsaturated cooking oil."
        ),

        // Stimulants
        IngredientRule(
            keywords = listOf("caffeine", "guarana", "coffee extract", "taurine"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "Stimulant ingredient; caution for hypertension, anxiety, pregnancy, or sleep.",
            conditionCautionTags = setOf(ProfileTag.CAFFEINE_SENSITIVITY, ProfileTag.HYPERTENSION_SODIUM)
        ),

        // Ambiguous additives / emulsifiers (source unspecified)
        IngredientRule(
            keywords = listOf("lecithin", "e322", "322(i)", "322", "emulsifier", "emulsifiers"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "Emulsifier with unspecified biological origin (commonly derived from soy or sunflower).",
            conditionCautionTags = setOf(ProfileTag.SOY)
        ),
        IngredientRule(
            keywords = listOf("flavour", "flavours", "flavor", "flavors", "flavouring", "flavoring", "artificial flavour", "nature identical"),
            baseVerdict = VerdictLevel.CAUTION,
            summary = "Generic flavouring blend whose exact sub-components are not itemized."
        ),

        // Well-established safe food ingredients & micronutrients
        IngredientRule(
            keywords = listOf(
                "water", "purified water", "carbonated water",
                "cocoa solids", "cocoa powder", "cocoa mass", "cocoa butter",
                "rice", "rice flour", "brown rice", "ragi", "jowar", "millet", "quinoa",
                "corn starch", "maize starch", "tapioca", "potato starch", "starch",
                "chia", "flax", "pumpkin seed", "sunflower seed", "raisin", "cranberry",
                "citric acid", "e330", "ascorbic acid", "e300", "vitamin c",
                "tocopherol", "e306", "e307", "vitamin e", "vitamin d", "vitamin b", "niacin", "iron", "zinc",
                "calcium carbonate", "e170", "pectin", "e440", "agar", "guar gum", "e412", "xanthan gum", "e415",
                "yeast", "vinegar", "turmeric", "cinnamon", "cardamom", "cumin", "ginger", "garlic", "black pepper", "pepper",
                "vanilla", "cocoa", "probiotic", "bacillus coagulans"
            ),
            baseVerdict = VerdictLevel.SAFE,
            summary = "Commonly recognized ingredient with no identified conflict against your recorded profile."
        )
    )

    /**
     * Analyzes [ingredients] strictly locally against [profile] and optional OCR [allergenDeclarations]
     * / [rawOcrText]. Never calls any network or Gemini endpoint.
     */
    fun analyze(
        ingredients: List<String>,
        profile: MedicalProfile,
        allergenDeclarations: List<String> = emptyList(),
        rawOcrText: String = ""
    ): IngredientAnalysis {
        val activeProfileTags = extractProfileTags(profile)
        val strictPenalties = AppPreferences.bool(AppPreferences.STRICT_SAFETY_PENALTIES, true)
        val allergenAlerts = AppPreferences.bool(AppPreferences.CRITICAL_ALLERGEN_ALERTS, true)
        val intoleranceAlerts = AppPreferences.bool(AppPreferences.INTOLERANCE_ALERTS, true)

        val breakdown = mutableListOf<IngredientVerdict>()
        val matchedAvoids = mutableListOf<Pair<String, String>>()
        val matchedCautions = mutableListOf<Pair<String, String>>()
        val unknownItems = mutableListOf<String>()
        var category = "food"

        for (rawItem in ingredients.distinctBy { it.trim().lowercase() }) {
            val item = rawItem.trim()
            if (item.isEmpty()) continue
            val verdict = evaluateSingleIngredient(
                ingredient = item,
                profile = profile,
                activeProfileTags = activeProfileTags,
                allergenAlerts = allergenAlerts,
                intoleranceAlerts = intoleranceAlerts
            )
            if (verdict.verdict == VerdictLevel.AVOID) {
                matchedAvoids += item to (verdict.conflict ?: item)
            } else if (verdict.verdict == VerdictLevel.CAUTION) {
                matchedCautions += item to (verdict.conflict ?: item)
            } else if (verdict.verdict == VerdictLevel.UNKNOWN) {
                unknownItems += item
            }

            if (looksLikeMedicine(item)) category = "medicine"
            breakdown += verdict
        }

        if (category == "food" && looksLikeMedicine(rawOcrText)) {
            category = "medicine"
        }

        // Cross-check OCR "Contains ..." / "Allergen advice" statements even if ingredient tokenization missed them
        val declarationConflicts = evaluateAllergenDeclarations(
            declarations = allergenDeclarations,
            profile = profile,
            activeProfileTags = activeProfileTags
        )
        declarationConflicts.forEach { (sourceText, conflictLabel) ->
            if (matchedAvoids.none { it.second.equals(conflictLabel, ignoreCase = true) }) {
                matchedAvoids += sourceText to conflictLabel
            }
        }

        val score = computeScore(
            breakdown = breakdown,
            declarationConflictCount = declarationConflicts.size,
            matchedCautions = matchedCautions,
            unknownCount = unknownItems.size,
            strictPenalties = strictPenalties
        )

        val badgeLabel = when {
            score >= 70 -> "Good match for you"
            score >= 40 -> "Use with care"
            else -> "Avoid"
        }

        val healthFactors = buildHealthFactors(
            breakdown = breakdown,
            declarationConflicts = declarationConflicts,
            unknownItems = unknownItems,
            allergenAlerts = allergenAlerts
        )

        val headline = buildHeadline(
            matchedAvoids = matchedAvoids,
            matchedCautions = matchedCautions,
            unknownItems = unknownItems,
            allergenAlerts = allergenAlerts
        )

        val safeCount = breakdown.count { it.verdict == VerdictLevel.SAFE }
        val cautionCount = breakdown.count { it.verdict == VerdictLevel.CAUTION }
        val avoidCount = breakdown.count { it.verdict == VerdictLevel.AVOID }
        val unknownCount = breakdown.count { it.verdict == VerdictLevel.UNKNOWN }

        val summary = buildString {
            append(
                "Offline analysis checked ${breakdown.size} ingredient" +
                    (if (breakdown.size == 1) "" else "s") +
                    " against the local ingredient database and your saved health profile: " +
                    "$safeCount safe, $cautionCount caution, $avoidCount avoid, $unknownCount unknown."
            )
            if (declarationConflicts.isNotEmpty()) {
                append(
                    " Label allergen declarations flagged conflicts with: " +
                        declarationConflicts.joinToString(", ") { it.second } + "."
                )
            }
            if (unknownCount > 0) {
                append(
                    " Unrecognized ingredients were kept as UNKNOWN and never assumed safe."
                )
            }
        }

        val uncertaintyNotes = buildList {
            if (unknownItems.isNotEmpty()) {
                add(
                    "Not in offline database (${unknownItems.joinToString(", ")}): marked UNKNOWN. " +
                        "Verify the label or re-analyze with Auto (Gemini Flash) when online."
                )
            }
            add(
                "Offline rule mode uses a curated ingredient database without cloud LLM reasoning; " +
                    "complex blends or unlisted regional additives may need manual verification."
            )
        }

        val recommendedIntake = when {
            matchedAvoids.isNotEmpty() ->
                "Avoid consuming this product because it conflicts with your recorded profile."
            matchedCautions.isNotEmpty() || unknownItems.isNotEmpty() ->
                "Use with care and verify flagged or unknown ingredients before consuming."
            else ->
                "No direct conflicts found offline; follow standard serving guidance on the pack."
        }

        return IngredientAnalysis(
            productName = inferProductName(rawOcrText),
            productCategory = category,
            safetyScore = score,
            badgeLabel = badgeLabel,
            headline = headline,
            aiSummary = summary,
            healthFactors = healthFactors,
            ingredientBreakdown = breakdown.take(IngredientParser.MAX_INGREDIENTS),
            recommendedIntake = recommendedIntake,
            uncertaintyNotes = uncertaintyNotes,
            engine = AiEngine.OFFLINE,
            usedFallback = false,
            fallbackNotice = null
        )
    }

    private fun evaluateSingleIngredient(
        ingredient: String,
        profile: MedicalProfile,
        activeProfileTags: Map<ProfileTag, String>,
        allergenAlerts: Boolean,
        intoleranceAlerts: Boolean
    ): IngredientVerdict {
        val normalized = ingredient.lowercase()

        // 1. Direct string/term match against user's explicit allergies & dietary restrictions first
        val directAllergyMatch = findDirectProfileMatch(normalized, profile.allergies)
        if (directAllergyMatch != null) {
            return IngredientVerdict(
                name = ingredient,
                verdict = VerdictLevel.AVOID,
                note = if (allergenAlerts) {
                    "Allergen alert: directly matches your recorded allergy ($directAllergyMatch)."
                } else {
                    "Matches your recorded allergy ($directAllergyMatch)."
                },
                conflict = directAllergyMatch
            )
        }

        val directDietMatch = findDirectProfileMatch(normalized, profile.dietaryRestrictions)
        if (directDietMatch != null) {
            return IngredientVerdict(
                name = ingredient,
                verdict = VerdictLevel.AVOID,
                note = "Directly conflicts with your dietary restriction ($directDietMatch).",
                conflict = directDietMatch
            )
        }

        val directConditionMatch = findDirectProfileMatch(normalized, profile.conditions)
        if (directConditionMatch != null) {
            return IngredientVerdict(
                name = ingredient,
                verdict = VerdictLevel.AVOID,
                note = "Conflicts with a recorded condition ($directConditionMatch).",
                conflict = directConditionMatch
            )
        }

        // 2. Database lookup (match keyword boundaries so e.g. "salt" matches "common salt" cleanly)
        val matchedRules = database.filter { rule ->
            rule.keywords.any { kw -> matchesKeyword(normalized, kw) }
        }

        if (matchedRules.isEmpty()) {
            return IngredientVerdict(
                name = ingredient,
                verdict = VerdictLevel.UNKNOWN,
                note = "Unrecognized ingredient not found in the offline database; never assumed safe."
            )
        }

        // Evaluate most restrictive rule outcome across all matching rules for compound ingredients
        // e.g. "Whole Wheat Flour & Milk Solids" matches both GLUTEN and DAIRY.
        val allergenConflict = matchedRules
            .flatMap { it.allergenTags }
            .firstNotNullOfOrNull { tag -> activeProfileTags[tag]?.let { label -> tag to label } }
        if (allergenConflict != null) {
            val (_, profileLabel) = allergenConflict
            return IngredientVerdict(
                name = ingredient,
                verdict = VerdictLevel.AVOID,
                note = "Contains or derives from $profileLabel, which conflicts with your profile.",
                conflict = profileLabel
            )
        }

        val dietConflict = matchedRules
            .flatMap { it.dietaryConflictTags }
            .firstNotNullOfOrNull { tag -> activeProfileTags[tag]?.let { label -> tag to label } }
        if (dietConflict != null) {
            val (_, profileLabel) = dietConflict
            return IngredientVerdict(
                name = ingredient,
                verdict = VerdictLevel.AVOID,
                note = "Conflicts with your $profileLabel restriction.",
                conflict = profileLabel
            )
        }

        val conditionConflict = matchedRules
            .flatMap { it.conditionCautionTags }
            .firstNotNullOfOrNull { tag -> activeProfileTags[tag]?.let { label -> tag to label } }
        if (conditionConflict != null) {
            val (tag, profileLabel) = conditionConflict
            val shouldHighlight = intoleranceAlerts || tag !in setOf(
                ProfileTag.LACTOSE, ProfileTag.DIABETES_SUGAR, ProfileTag.HYPERTENSION_SODIUM
            )
            val primarySummary = matchedRules.first().summary
            return IngredientVerdict(
                name = ingredient,
                verdict = VerdictLevel.CAUTION,
                note = if (shouldHighlight) {
                    "$primarySummary Relevant to your $profileLabel profile."
                } else {
                    primarySummary
                },
                conflict = if (shouldHighlight) profileLabel else null
            )
        }

        val cautionRule = matchedRules.firstOrNull { it.baseVerdict == VerdictLevel.CAUTION }
        if (cautionRule != null) {
            return IngredientVerdict(
                name = ingredient,
                verdict = VerdictLevel.CAUTION,
                note = cautionRule.summary
            )
        }

        return IngredientVerdict(
            name = ingredient,
            verdict = VerdictLevel.SAFE,
            note = matchedRules.first().summary
        )
    }

    private fun evaluateAllergenDeclarations(
        declarations: List<String>,
        profile: MedicalProfile,
        activeProfileTags: Map<ProfileTag, String>
    ): List<Pair<String, String>> {
        if (declarations.isEmpty()) return emptyList()
        val conflicts = mutableListOf<Pair<String, String>>()
        for (decl in declarations) {
            val lower = decl.lowercase()
            findDirectProfileMatch(lower, profile.allergies)?.let { label ->
                conflicts += decl to label
                continue
            }
            database.forEach { rule ->
                if (rule.keywords.any { kw -> matchesKeyword(lower, kw) }) {
                    rule.allergenTags.firstNotNullOfOrNull { activeProfileTags[it] }?.let { label ->
                        conflicts += decl to label
                    }
                }
            }
        }
        return conflicts.distinctBy { it.second.lowercase() }
    }

    private fun extractProfileTags(profile: MedicalProfile): Map<ProfileTag, String> {
        val tags = LinkedHashMap<ProfileTag, String>()

        fun register(entry: String, tag: ProfileTag) {
            tags.putIfAbsent(tag, entry)
        }

        val entries = profile.allergies + profile.dietaryRestrictions + profile.conditions + profile.healthNotes
        for (raw in entries) {
            val item = raw.trim()
            if (item.isEmpty()) continue
            val lower = item.lowercase()
            if (lower.contains("peanut") || lower.contains("groundnut")) register(item, ProfileTag.PEANUT)
            if (lower.contains("tree nut") || lower.contains("almond") || lower.contains("cashew") ||
                lower.contains("walnut") || lower.contains("hazelnut") || lower.contains("pistachio") ||
                (lower.contains("nut") && !lower.contains("peanut") && !lower.contains("coconut"))
            ) register(item, ProfileTag.TREE_NUT)
            if (lower.contains("milk") || lower.contains("dairy") || lower.contains("casein") || lower.contains("whey")) {
                register(item, ProfileTag.DAIRY)
                register(item, ProfileTag.LACTOSE)
            }
            if (lower.contains("lactose")) register(item, ProfileTag.LACTOSE)
            if (lower.contains("gluten") || lower.contains("celiac") || lower.contains("coeliac") || lower.contains("wheat")) {
                register(item, ProfileTag.GLUTEN)
            }
            if (lower.contains("soy")) register(item, ProfileTag.SOY)
            if (lower.contains("egg")) register(item, ProfileTag.EGG)
            if (lower.contains("fish") && !lower.contains("shellfish")) register(item, ProfileTag.FISH)
            if (lower.contains("shellfish") || lower.contains("shrimp") || lower.contains("prawn") ||
                lower.contains("crab") || lower.contains("crustacean")
            ) register(item, ProfileTag.SHELLFISH)
            if (lower.contains("sesame")) register(item, ProfileTag.SESAME)
            if (lower.contains("mustard")) register(item, ProfileTag.MUSTARD)
            if (lower.contains("sulfit") || lower.contains("sulphit")) register(item, ProfileTag.SULFITE)
            if (lower.contains("penicillin") || lower.contains("amoxicillin")) register(item, ProfileTag.PENICILLIN)
            if (lower.contains("nsaid") || lower.contains("ibuprofen") || lower.contains("aspirin")) register(item, ProfileTag.NSAID)
            if (lower.contains("diabet") || lower.contains("glucose") || lower.contains("blood sugar") ||
                lower.contains("low sugar") || lower.contains("sugar-free") || lower.contains("sugar free") ||
                lower.contains("keto")
            ) register(item, ProfileTag.DIABETES_SUGAR)
            if (lower.contains("hypertension") || lower.contains("blood pressure") || lower.contains("low sodium") ||
                lower.contains("sodium") || lower.contains("kidney") || lower.contains("renal")
            ) register(item, ProfileTag.HYPERTENSION_SODIUM)
            if (lower.contains("cholesterol") || lower.contains("heart") || lower.contains("cardio") ||
                lower.contains("lipid")
            ) register(item, ProfileTag.HEART_CHOLESTEROL)
            if (lower.contains("pregnan") || lower.contains("caffeine") || lower.contains("anxiety")) {
                register(item, ProfileTag.CAFFEINE_SENSITIVITY)
            }
            if (lower.contains("vegan")) register(item, ProfileTag.VEGAN)
            if (lower.contains("vegetarian") || lower.contains("veg only")) register(item, ProfileTag.VEGETARIAN)
            if (lower.contains("halal") || lower.contains("kosher") || lower.contains("no pork")) {
                register(item, ProfileTag.HALAL_PORK)
            }
            if (lower.contains("phenylketonuria") || lower.contains("pku")) register(item, ProfileTag.PKU)
        }
        return tags
    }

    private fun findDirectProfileMatch(ingredientLower: String, profileEntries: List<String>): String? {
        for (raw in profileEntries) {
            val term = raw.trim().lowercase()
            if (term.length < 3) continue
            val stem = term.removeSuffix("s").removeSuffix(" allergy").removeSuffix(" intolerance").trim()
            if (stem.length >= 3 && matchesKeyword(ingredientLower, stem)) {
                return raw.trim()
            }
        }
        return null
    }

    private fun matchesKeyword(haystackLower: String, keywordLower: String): Boolean {
        if (haystackLower == keywordLower) return true
        val escaped = Regex.escape(keywordLower)
        return Regex("""(?i)(?<![a-z0-9])$escaped(?:s|es)?(?![a-z0-9])""").containsMatchIn(haystackLower)
    }

    private fun computeScore(
        breakdown: List<IngredientVerdict>,
        declarationConflictCount: Int,
        matchedCautions: List<Pair<String, String>>,
        unknownCount: Int,
        strictPenalties: Boolean
    ): Int {
        val avoidCount = breakdown.count { it.verdict == VerdictLevel.AVOID } + declarationConflictCount
        if (avoidCount > 0 && strictPenalties) {
            val penaltyScore = (39 - (avoidCount - 1) * 10 - (matchedCautions.size * 3)).coerceIn(5, 39)
            return penaltyScore
        }

        var score = 92
        score -= avoidCount * 30
        matchedCautions.forEach { (_, conflict) ->
            // Profile-linked cautions weigh more than general nutrition cautions.
            score -= if (breakdown.any { it.conflict == conflict }) 16 else 8
        }
        score -= (unknownCount * 9).coerceAtMost(45)

        if (breakdown.isNotEmpty() && unknownCount == breakdown.size) {
            score = score.coerceAtMost(52)
        } else if (unknownCount > 0 && score >= 70 && unknownCount * 2 >= breakdown.size) {
            score = 65
        }
        return score.coerceIn(5, 95)
    }

    private fun buildHealthFactors(
        breakdown: List<IngredientVerdict>,
        declarationConflicts: List<Pair<String, String>>,
        unknownItems: List<String>,
        allergenAlerts: Boolean
    ): List<HealthFactor> = buildList {
        val avoidVerdicts = breakdown.filter { it.verdict == VerdictLevel.AVOID }
        if (avoidVerdicts.isNotEmpty() || declarationConflicts.isNotEmpty()) {
            val names = (avoidVerdicts.map { it.name } + declarationConflicts.map { it.first }).distinct()
            val conflicts = (avoidVerdicts.mapNotNull { it.conflict } + declarationConflicts.map { it.second }).distinct()
            add(
                HealthFactor(
                    label = if (allergenAlerts) "Allergen" else "Profile conflict",
                    detail = "${names.joinToString(", ")} conflict(s) with: ${conflicts.joinToString(", ")}.",
                    direction = FactorDirection.CAUTION
                )
            )
        }

        val profileCautions = breakdown.filter { it.verdict == VerdictLevel.CAUTION && !it.conflict.isNullOrBlank() }
        if (profileCautions.isNotEmpty()) {
            add(
                HealthFactor(
                    label = "Condition / sensitivity caution",
                    detail = "${profileCautions.joinToString(", ") { it.name }} may impact: " +
                        "${profileCautions.mapNotNull { it.conflict }.distinct().joinToString(", ")}.",
                    direction = FactorDirection.CAUTION
                )
            )
        }

        val generalCautions = breakdown.filter { it.verdict == VerdictLevel.CAUTION && it.conflict.isNullOrBlank() }
        if (generalCautions.isNotEmpty()) {
            add(
                HealthFactor(
                    label = "Ingredients to moderate",
                    detail = "${generalCautions.joinToString(", ") { it.name }} flagged for general nutrition or unspecified source.",
                    direction = FactorDirection.CAUTION
                )
            )
        }

        if (unknownItems.isNotEmpty()) {
            add(
                HealthFactor(
                    label = "Unrecognized ingredients",
                    detail = "${unknownItems.size} item(s) (${unknownItems.joinToString(", ")}) are not in the offline database and remain UNKNOWN.",
                    direction = FactorDirection.UNKNOWN
                )
            )
        }

        val safeCount = breakdown.count { it.verdict == VerdictLevel.SAFE }
        if (safeCount > 0 && avoidVerdicts.isEmpty() && declarationConflicts.isEmpty()) {
            add(
                HealthFactor(
                    label = "Verified safe ingredients",
                    detail = "$safeCount recognized ingredient(s) showed no conflict with your saved profile.",
                    direction = FactorDirection.POSITIVE
                )
            )
        }
    }

    private fun buildHeadline(
        matchedAvoids: List<Pair<String, String>>,
        matchedCautions: List<Pair<String, String>>,
        unknownItems: List<String>,
        allergenAlerts: Boolean
    ): String = when {
        matchedAvoids.isNotEmpty() -> {
            val prefix = if (allergenAlerts) "Allergen / profile conflict: " else "Avoid: "
            prefix + matchedAvoids.joinToString(", ") { "${it.first} (${it.second})" }
        }
        matchedCautions.isNotEmpty() && unknownItems.isNotEmpty() ->
            "Use with care: ${matchedCautions.size} flagged and ${unknownItems.size} unrecognized offline"
        matchedCautions.isNotEmpty() ->
            "Use with care: ${matchedCautions.joinToString(", ") { it.first }}"
        unknownItems.isNotEmpty() ->
            "Unverified ingredients found (${unknownItems.size} unknown offline)"
        else ->
            "No conflicts found against your saved profile"
    }

    private fun looksLikeMedicine(text: String): Boolean {
        if (text.isBlank()) return false
        return Regex("""(?i)\b(paracetamol|acetaminophen|ibuprofen|amoxicillin|penicillin|aspirin|metformin|tablet|capsule|syrup|excipients|active ingredient)\b""")
            .containsMatchIn(text)
    }

    private fun inferProductName(rawOcrText: String): String {
        val candidate = rawOcrText.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.length in 4..48 &&
                    line.any { it.isLetter() } &&
                    !line.contains("ingredients", ignoreCase = true) &&
                    !line.contains("nutrition", ignoreCase = true) &&
                    !line.contains("allergen", ignoreCase = true)
            }
        return candidate ?: "Scanned product"
    }
}
