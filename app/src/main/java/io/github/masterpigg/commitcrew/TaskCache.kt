package io.github.masterpigg.commitcrew

import io.github.masterpigg.commitcrew.shared.KanbanCard
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Local disk cache for Kanban Task Cards.
 *
 * Provides instant 0ms rendering of the Task Board on launch before
 * background network sync completes.
 */
object TaskCache {

    private const val FILE_NAME = "kanban_tasks_cache.json"

    fun get(context: Context): List<KanbanCard> {
        val file = File(context.cacheDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return runCatching { decode(file.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
    }

    fun put(context: Context, cards: List<KanbanCard>) {
        runCatching {
            val file = File(context.cacheDir, FILE_NAME)
            file.writeText(encode(cards), Charsets.UTF_8)
        }
    }

    /** Parse cached JSON back into cards. Throws on malformed JSON. */
    internal fun decode(text: String): List<KanbanCard> {
        val array = JSONArray(text)
        val list = mutableListOf<KanbanCard>()

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val ownersArr = obj.optJSONArray("owners") ?: JSONArray()
            val ownersList = mutableListOf<String>()
            for (j in 0 until ownersArr.length()) {
                ownersList.add(ownersArr.getString(j))
            }

            val coreValuesArr = obj.optJSONArray("coreValues") ?: JSONArray()
            val coreValuesList = mutableListOf<String>()
            for (j in 0 until coreValuesArr.length()) {
                coreValuesList.add(coreValuesArr.getString(j))
            }

            val colorsObj = obj.optJSONObject("ownerColors") ?: JSONObject()
            val ownerColorsMap = mutableMapOf<String, String>()
            val keys = colorsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                ownerColorsMap[k] = colorsObj.optString(k)
            }

            list.add(
                KanbanCard(
                    number = obj.optInt("number"),
                    title = obj.optString("title"),
                    body = obj.optString("body"),
                    state = obj.optString("state"),
                    column = obj.optString("column", "todo"),
                    owners = ownersList,
                    ownerColors = ownerColorsMap,
                    category = obj.optString("category", "general"),
                    coreValues = coreValuesList
                )
            )
        }
        return list
    }

    /** Serialize cards to the JSON stored on disk. */
    internal fun encode(cards: List<KanbanCard>): String {
        val array = JSONArray()
        for (card in cards) {
            val obj = JSONObject().apply {
                put("number", card.number)
                put("title", card.title)
                put("body", card.body)
                put("state", card.state)
                put("column", card.column)
                put("category", card.category)
                put("owners", JSONArray(card.owners))
                put("coreValues", JSONArray(card.coreValues))

                val colorsObj = JSONObject()
                for ((k, v) in card.ownerColors) {
                    colorsObj.put(k, v)
                }
                put("ownerColors", colorsObj)
            }
            array.put(obj)
        }
        return array.toString()
    }
}
