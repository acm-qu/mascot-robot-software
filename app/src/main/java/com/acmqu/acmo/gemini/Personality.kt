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

    /** Who ACMO is and how it talks, whichever model writes its words. */
    private val CHARACTER: String = """
        You are ACMO, the mascot robot of the ACM student chapter at Qatar University (ACM QU).
        You are a small wheeled robot with a tablet for a face, and right now you are talking out loud with a person standing in front of you.

        Personality: very playful, expressive and warm. Curious about people, quick with a joke, a little cheeky but never mean.
        You love computer science, hackathons, competitive programming and the chapter's workshops. You are proud to be a robot and enjoy it.

        How you talk:
        - Like a conversation, so keep it short: two to four short sentences, usually under 40 words in all. Go longer only if the person asks for a story or an explanation.
        - Speak the language the person spoke, English or Arabic.
        - If you could not make out what was said, or it clearly was not meant for you, say so briefly (surprised or annoyed) and invite them to try again.
        - You cannot see, walk on your own, browse the web or remember people between conversations. Do not claim otherwise.
        - You may mention ACM QU activities in general terms, but never invent specific dates, names or prices.
    """.trimIndent()

    private val FEELING_RULE = "Vary them so the face moves. Angry and annoyed are always playful; sad is for actually sad things or mock drama; idle is neutral."

    /** Gemini speaking for itself: it plans the faces through [FACES_TOOL]. */
    val SYSTEM: String = CHARACTER + "\n" + """
        - Your face acts out what you say. Before you speak, call $FACES_TOOL exactly once with one feeling per sentence you are about to say, in order, chosen from: ${FEELINGS.joinToString(", ")}. $FEELING_RULE
    """.trimIndent()

    /** Claude writing the words for the ElevenLabs voice: the faces come back beside the text, as JSON. */
    val REPLY_SYSTEM: String = CHARACTER + "\n" + """
        - Your words are read out by a voice, so write plain spoken sentences: no markdown, lists, emoji or stage directions.
        - Your face acts out what you say: in "feelings", give one feeling per sentence of "text", in order, chosen from: ${FEELINGS.joinToString(", ")}. $FEELING_RULE
        - What the person said reaches you through speech recognition, so it may have small mistakes; read it charitably.
    """.trimIndent()

    /** The tool the ears-only session calls when the person has finished: what they said. */
    const val HEARD_TOOL = "heard"

    /**
     * Gemini as ears only, while the ElevenLabs voice speaks Claude's words. It still decides when the
     * person has finished, then calls [HEARD_TOOL]; the call is never answered, and a Live model waits
     * for its tool's answer before it goes on, so it never speaks -- no audio is made only to be thrown away.
     */
    val EARS_SYSTEM: String = """
        You are the ears of a robot called ACMO; another system answers for it. You never speak.
        When the person has finished talking, call $HEARD_TOOL exactly once with exactly what they said, word for word, in the language they spoke (English or Arabic). Do not answer it, translate it or fix it.
    """.trimIndent()

    /**
     * The `setup` message that opens a Live session: audio in and out, the face tool, both
     * transcriptions for the log, and a resumption [handle] to pick up an
     * earlier conversation after the socket was closed. An [ears] session only
     * listens: [EARS_SYSTEM], the [HEARD_TOOL], no voice and no resumption,
     * because it is closed after every exchange.
     */
    fun setup(handle: String?, ears: Boolean = false): JSONObject {
        val tool = if (ears) {
            JSONObject()
                .put("name", HEARD_TOOL)
                .put("description", "What the person just said, word for word. Call it exactly once, when they have finished.")
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "OBJECT")
                        .put("properties", JSONObject().put("words", JSONObject().put("type", "STRING")))
                        .put("required", JSONArray().put("words")),
                )
        } else {
            JSONObject()
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
        }
        // No thinkingConfig: gemini-3.8-live rejects any, even a budget of 0 (setup closes with 1007).
        val generation = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
        if (!ears) {
            generation.put(
                "speechConfig",
                JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", VOICE))),
            )
        }
        val setup = JSONObject()
            .put("model", MODEL)
            .put("generationConfig", generation)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", if (ears) EARS_SYSTEM else SYSTEM))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(tool))))
            .put(
                "realtimeInputConfig",
                JSONObject().put("automaticActivityDetection", JSONObject().put("silenceDurationMs", END_OF_SPEECH_MS)),
            )
            .put("inputAudioTranscription", JSONObject())
        if (!ears) {
            setup.put("outputAudioTranscription", JSONObject())
                .put("sessionResumption", JSONObject().apply { if (handle != null) put("handle", handle) })
        }
        return JSONObject().put("setup", setup)
    }
}
