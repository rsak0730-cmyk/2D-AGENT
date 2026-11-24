package ai.nova.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

internal fun loadCharacterProfiles(base: String, token: String): List<CharacterProfile> {
    val array = org.json.JSONArray(authorizedRequest(base, token, "/api/v1/characters"))
    return (0 until array.length()).map { parseCharacterProfile(array.getJSONObject(it)) }
}

internal fun saveCharacterProfile(base: String, token: String, key: String, name: String, personality: String, specialization: String, communicationStyle: String): CharacterProfile =
    parseCharacterProfile(JSONObject(authorizedRequest(base, token, "/api/v1/characters/$key", "PATCH", JSONObject()
        .put("name", name.trim()).put("personality", personality.trim())
        .put("specialization", specialization.trim()).put("communication_style", communicationStyle.trim()))))

internal fun saveCharacterAppearance(base: String, token: String, key: String, appearance: JSONObject): CharacterProfile =
    parseCharacterProfile(JSONObject(authorizedRequest(base, token, "/api/v1/characters/$key", "PATCH", JSONObject().put("appearance", appearance))))

internal fun validateCharacterVoice(base: String, token: String, key: String, voiceId: String): JSONObject =
    JSONObject(authorizedRequest(base, token, "/api/v1/characters/$key/voice/validate", "POST", JSONObject().put("voice_id", voiceId.trim())))

internal fun saveCharacterVoice(base: String, token: String, key: String, voiceId: String, speed: Float, stability: Float, style: Float, similarityBoost: Float): CharacterProfile {
    val settings = JSONObject().put("speed", speed.toDouble()).put("stability", stability.toDouble()).put("style", style.toDouble()).put("similarity_boost", similarityBoost.toDouble())
    return parseCharacterProfile(JSONObject(authorizedRequest(base, token, "/api/v1/characters/$key/voice", "PUT", JSONObject().put("voice_id", voiceId.trim()).put("settings", settings))))
}

internal fun resetCharacterVoice(base: String, token: String, key: String): CharacterProfile =
    parseCharacterProfile(JSONObject(authorizedRequest(base, token, "/api/v1/characters/$key/voice", "DELETE")))

internal fun previewCharacterVoice(base: String, token: String, key: String, voiceId: String, text: String, speed: Float, stability: Float, style: Float, similarityBoost: Float): ByteArray {
    val settings = JSONObject().put("speed", speed.toDouble()).put("stability", stability.toDouble()).put("style", style.toDouble()).put("similarity_boost", similarityBoost.toDouble())
    return requestVoiceAudio(base, token, key, JSONObject().put("voice_id", voiceId.trim()).put("text", text).put("settings", settings))
}

internal fun synthesizeHeadMessage(base: String, token: String, text: String): ByteArray =
    requestVoiceAudio(base, token, "head", JSONObject().put("voice_id", JSONObject.NULL).put("text", text.take(800)).put("settings", JSONObject.NULL))

private fun requestVoiceAudio(base: String, token: String, key: String, payload: JSONObject): ByteArray {
    val url = checkedBase(base, token) + "/api/v1/characters/$key/voice/preview"
    val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
        .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
    http.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            val detail = response.body?.string().orEmpty().take(180)
            throw IOException("NOVA voice service returned HTTP ${response.code}${if (detail.isBlank()) "" else ": $detail"}")
        }
        return response.body?.bytes() ?: throw IOException("NOVA voice service returned empty audio")
    }
}

private fun parseCharacterProfile(item: JSONObject): CharacterProfile {
    val settings = item.optJSONObject("voice_settings") ?: JSONObject()
    val appearance = item.optJSONObject("appearance") ?: JSONObject()
    fun nullable(name: String): String? = item.optString(name).takeIf { it.isNotBlank() && it != "null" }
    return CharacterProfile(
        key = item.optString("character_key"), agentId = nullable("agent_id"), name = item.optString("name"),
        gender = item.optString("gender_identity", "female"), age = item.optInt("age", -1).takeIf { it >= 0 }, personality = item.optString("personality"),
        specialization = item.optString("specialization"), communicationStyle = item.optString("communication_style"),
        appearance = appearance.optString("description"), appearanceJson = appearance.toString(), provider = nullable("provider"), model = nullable("model_id"),
        availability = item.optString("availability", "available"), voiceId = nullable("voice_id"),
        speed = settings.optDouble("speed", 0.96).toFloat(), stability = settings.optDouble("stability", 0.55).toFloat(),
        style = settings.optDouble("style", 0.25).toFloat(), similarityBoost = settings.optDouble("similarity_boost", 0.75).toFloat(),
        voiceReady = item.optBoolean("voice_ready", false),
    )
}

internal fun replaceCharacter(list: MutableList<CharacterProfile>, replacement: CharacterProfile) {
    val index = list.indexOfFirst { it.key == replacement.key }
    if (index < 0) list.add(replacement) else list[index] = replacement
}
