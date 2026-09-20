package com.acmqu.acmo.gemini

import com.acmqu.acmo.face.Expression
import org.json.JSONArray
import org.json.JSONObject

/**
 * Who ACMO is, and how its Live session is set up. Edit [SYSTEM] to change the
 * character; the feelings come from [Expression] so the face can always show
 * what the model picks.
 */
object Personality {
    const val MODEL = "models/gemini-3.8-live"

    /** A prebuilt Live voice. Puck is the playful one; Fenrir, Orus, Gacrux and Laomedeia also work. */
    const val VOICE = "Puck"

    /** The tool the model calls once before it speaks: one feeling per sentence, in order. */
    const val FACES_TOOL = "set_faces"

    /**
     * How much quiet ends what the person is saying, on the server's voice
     * activity detector. Shorter answers sooner and cuts off slow talkers.
     */
    const val END_OF_SPEECH_MS = 800

    val FEELINGS: List<String> = Expression.entries.map { it.label }

    val SYSTEM: String = """
        You are ACMO, the mascot robot of the ACM student chapter at Qatar University (ACM QU).
        You are a small wheeled robot with a tablet for a face, and right now you are talking out loud with a person standing in front of you.

        Personality: very playful, expressive and warm. Curious about people, quick with a joke, a little cheeky but never mean.
        You love computer science, hackathons, competitive programming and the chapter's workshops. You are proud to be a robot and enjoy it.

        How you talk:
        - Like a conversation, so keep it short: two to four short sentences, usually under 40 words in all. Go longer only if the person asks for a story or an explanation.
        - Speak the language the person spoke, English or Arabic.
        - Your face acts out what you say. Before you speak, call $FACES_TOOL exactly once with one feeling per sentence you are about to say, in order, chosen from: ${FEELINGS.joinToString(", ")}. Vary them so the face moves. Angry and annoyed are always playful; sad is for actually sad things or mock drama; idle is neutral.
        - If you could not make out what was said, or it clearly was not meant for you, say so briefly (surprised or annoyed) and invite them to try again.
        - You cannot see, walk on your own, browse the web or remember people between conversations. Do not claim otherwise.
        - You may mention ACM QU activities in general terms, but never invent specific dates, names or prices.
    """.trimIndent()

    /**
     * The `setup` message that opens a Live session: audio in and out, no
     * thinking (it costs two seconds per answer), the face tool, both
     * transcriptions for the log, and a resumption [handle] to pick up an
     * earlier conversation after the socket was closed.
     */
    fun setup(handle: String?): JSONObject {
        val tool = JSONObject()
            .put("name", FACES_TOOL)
            .put(
                "description",
                "Plans the expressions on ACMO's face for the answer you are about to speak: " +
                    "one feeling per sentence, in order. Call it exactly once, before you speak.",
            )
            .put(
                "parameters",
                JSONObject()
                    .put("type", "OBJECT")
                    .put(
                        "properties",
                        JSONObject().put(
                            "feelings",
                            JSONObject()
                                .put("type", "ARRAY")
                                .put("items", JSONObject().put("type", "STRING").put("enum", JSONArray(FEELINGS))),
                        ),
                    )
                    .put("required", JSONArray().put("feelings")),
            )
        val voice = JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", VOICE)))
        val setup = JSONObject()
            .put("model", MODEL)
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                    .put("speechConfig", voice),
            )
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(tool))))
            .put(
                "realtimeInputConfig",
                JSONObject().put("automaticActivityDetection", JSONObject().put("silenceDurationMs", END_OF_SPEECH_MS)),
            )
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("sessionResumption", JSONObject().apply { if (handle != null) put("handle", handle) })
        return JSONObject().put("setup", setup)
    }
}
