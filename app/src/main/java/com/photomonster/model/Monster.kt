package com.photomonster.model

import com.google.android.gms.maps.model.LatLng
import java.util.UUID

enum class MonsterType(val displayName: String, val emoji: String) {
    NORMAL("ノーマル", "🐾"),
    FIRE("ほのお", "🔥"),
    WATER("みず", "💧"),
    GRASS("くさ", "🌿"),
    ELECTRIC("でんき", "⚡"),
    DARK("あく", "🌙"),
    LIGHT("ひかり", "☀️")
}

data class Monster(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: MonsterType,
    val latLng: LatLng,
    val hp: Int,
    val attack: Int,
    val defense: Int,
    val spawnTime: Long = System.currentTimeMillis()
)
