package com.fll.pushtogithub

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

    fun get(context: Context): List<GitHubClient.KanbanCard> {
        val file = File(context.cacheDir, FILE_NAME)
        if (!file.exists()) return emptyList()

        return runCatching {
            val text = file.readText(Charsets.UTF_8)
            val array = JSONArray(text)
            val list = mutableListOf<GitHubClient.KanbanCard>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val ownersArr = obj.optJSONArray("owners") ?: JSONArray()
                val ownersList = mutableListOf<String>()
                for (j in 0 until ownersArr.length()) {
                    ownersList.add(ownersArr.getString(j))
                }

                val colorsObj = obj.optJSONObject("ownerColors") ?: JSONObject()
                val ownerColorsMap = mutableMapOf<String, String>()
                val keys = colorsObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    ownerColorsMap[k] = colorsObj.optString(k)
                }

                list.add(
                    GitHubClient.KanbanCard(
                        number = obj.optInt("number"),
                        title = obj.optString("title"),
                        body = obj.optString("body"),
                        state = obj.optString("state"),
                        column = obj.optString("column", "todo"),
                        owners = ownersList,
                        ownerColors = ownerColorsMap,
                        category = obj.optString("category", "general")
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    fun put(context: Context, cards: List<GitHubClient.KanbanCard>) {
        runCatching {
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

                    val colorsObj = JSONObject()
                    for ((k, v) in card.ownerColors) {
                        colorsObj.put(k, v)
                    }
                    put("ownerColors", colorsObj)
                }
                array.put(obj)
            }

            val file = File(context.cacheDir, FILE_NAME)
            file.writeText(array.toString(), Charsets.UTF_8)
        }
    }
}
