package com.sharpcad.document

/**
 * A physically based surface look: base colour (sRGB 0xRRGGBB), metalness 0..1 and
 * roughness 0..1 (0 = mirror, 1 = fully diffuse).
 */
data class MaterialPreset(
    val id: String,
    val name: String,
    val category: String,
    val color: Int,
    val metallic: Float,
    val roughness: Float,
)

/** The built-in material presets, grouped by category. Ids are stable across releases. */
object MaterialLibrary {

    const val DEFAULT_ID = "special.default"

    val presets: List<MaterialPreset> = buildList {
        fun add(category: String, name: String, color: Int, metallic: Float, roughness: Float) {
            val id = "${category.lowercase()}.${name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')}"
            add(MaterialPreset(id, name, category, color, metallic, roughness))
        }

        // Special
        add("Special", "Default", 0xB9C4D6, 0f, 0.55f)
        add("Special", "White", 0xF2F2F2, 0f, 0.5f)
        add("Special", "Black", 0x1A1A1A, 0f, 0.5f)
        add("Special", "Carbon fibre", 0x202226, 0.2f, 0.3f)

        // Metals in three finishes
        val metals = listOf(
            "Steel" to 0x9DA3A8, "Stainless steel" to 0xC4C7C9, "Aluminium" to 0xD3D6D9, "Copper" to 0xD08A63,
            "Brass" to 0xD9B66B, "Bronze" to 0xB0814F, "Gold" to 0xF0C35A, "Silver" to 0xE6E6E6,
            "Titanium" to 0xA8A39A, "Iron" to 0x7E7E7E,
        )
        for ((name, color) in metals) {
            add("Metal", "$name polished", color, 1f, 0.12f)
            add("Metal", "$name brushed", color, 1f, 0.35f)
            add("Metal", "$name matte", color, 1f, 0.6f)
        }
        add("Metal", "Chrome", 0xF5F6F7, 1f, 0.05f)
        add("Metal", "Nickel", 0xC9C2B4, 1f, 0.25f)
        add("Metal", "Zinc", 0xBFC4C8, 1f, 0.45f)
        for ((name, color) in listOf("black" to 0x2A2C30, "red" to 0xB3242C, "blue" to 0x2856B8, "gold" to 0xC9A24A, "silver" to 0xBFC3C7)) {
            add("Metal", "Anodised aluminium $name", color, 0.85f, 0.3f)
        }

        // Plastics in sixteen colours, glossy and matte
        val colors = listOf(
            "White" to 0xF0F0EE, "Light grey" to 0xBDBDBD, "Dark grey" to 0x4A4A4A, "Black" to 0x161616,
            "Red" to 0xC62828, "Orange" to 0xEF6C00, "Yellow" to 0xF9C80E, "Lime" to 0x8BC34A,
            "Green" to 0x2E7D32, "Teal" to 0x00897B, "Cyan" to 0x00ACC1, "Blue" to 0x1565C0,
            "Navy" to 0x1A237E, "Purple" to 0x6A1B9A, "Pink" to 0xEC407A, "Brown" to 0x6D4C41,
        )
        for ((name, color) in colors) {
            add("Plastic", "$name glossy", color, 0f, 0.22f)
            add("Plastic", "$name matte", color, 0f, 0.65f)
        }

        // Rubber
        for ((name, color) in listOf("Black" to 0x1C1C1C, "Grey" to 0x5A5A5A, "Red" to 0x8E2020, "Blue" to 0x1F3A70)) {
            add("Rubber", "Rubber ${name.lowercase()}", color, 0f, 0.92f)
        }

        // Car paint
        for ((name, color) in listOf(
            "Racing red" to 0xB71C1C, "Midnight blue" to 0x0D1B4C, "Pearl white" to 0xF5F3EE, "Graphite" to 0x3B3F45,
            "British green" to 0x173B2A, "Sunset orange" to 0xE65100, "Silver" to 0xB8BCC2, "Deep black" to 0x0C0C0C,
        )) add("Paint", "Paint ${name.lowercase()}", color, 0.3f, 0.25f)

        // Wood
        for ((name, color) in listOf(
            "Oak" to 0xB98D5A, "Walnut" to 0x5D4030, "Maple" to 0xE0C391, "Cherry" to 0x9A4E2F,
            "Pine" to 0xD8B57A, "Mahogany" to 0x6B2E1F, "Bamboo" to 0xD6BE7E, "Ebony" to 0x2B211C,
        )) add("Wood", name, color, 0f, 0.7f)

        // Stone and ceramic
        add("Stone", "Marble white", 0xEDEBE6, 0f, 0.25f)
        add("Stone", "Granite", 0x6E6A66, 0f, 0.55f)
        add("Stone", "Concrete", 0x9E9C98, 0f, 0.9f)
        add("Stone", "Slate", 0x45494F, 0f, 0.75f)
        add("Stone", "Porcelain", 0xF7F7F4, 0f, 0.12f)
        add("Stone", "Terracotta", 0xB5562F, 0f, 0.8f)

        // Glass (shown opaque and tinted for now)
        add("Glass", "Glass clear", 0xD7E3E8, 0f, 0.04f)
        add("Glass", "Glass smoke", 0x4F555B, 0f, 0.04f)
        add("Glass", "Glass green", 0x7FB59A, 0f, 0.04f)
        add("Glass", "Glass amber", 0xC88A2E, 0f, 0.04f)

        // Fabric and leather
        add("Fabric", "Leather brown", 0x6B4226, 0f, 0.6f)
        add("Fabric", "Leather black", 0x1E1C1B, 0f, 0.55f)
        add("Fabric", "Felt grey", 0x7D7F82, 0f, 1f)
        add("Fabric", "Denim", 0x35507A, 0f, 0.95f)
    }

    private val byId = presets.associateBy { it.id }

    val categories: List<String> = presets.map { it.category }.distinct()

    val default: MaterialPreset = byId.getValue(DEFAULT_ID)

    fun find(id: String?): MaterialPreset = id?.let { byId[it] } ?: default
}
