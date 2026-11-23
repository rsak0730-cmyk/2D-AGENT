package ai.nova.app

/** Stable, role-based visual identity for the office sprite renderer. */
internal data class OfficeLook(val shirtArgb: Int, val hairArgb: Int)

private val roleShirts = mapOf(
    "manager" to 0xFFB98B47.toInt(),
    "android" to 0xFF527ED3.toInt(),
    "web" to 0xFF3D9C90.toInt(),
    "game" to 0xFF8E70BE.toInt(),
    "ui-ux" to 0xFFD06E91.toInt(),
    "backend" to 0xFF4D79A8.toInt(),
    "database" to 0xFFB36F47.toInt(),
    "security" to 0xFF566579.toInt(),
    "qa" to 0xFF4B9D69.toInt(),
    "research" to 0xFF547FA2.toInt(),
    "documentation" to 0xFFB486C2.toInt(),
)

internal fun officeLook(identity: String): OfficeLook {
    val role = identity.lowercase()
    val fallback = listOf(0xFF5D82D1, 0xFFCC765E, 0xFF8E70BE, 0xFF4D9C88, 0xFFD09B4A, 0xFF568FA8)
    val shirt = roleShirts[role] ?: fallback[Math.floorMod(role.hashCode(), fallback.size)].toInt()
    val hair = if (Math.floorMod(role.hashCode(), 2) == 0) 0xFF483A39.toInt() else 0xFF332F40.toInt()
    return OfficeLook(shirt, hair)
}
