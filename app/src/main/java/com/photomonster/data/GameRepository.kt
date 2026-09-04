package com.photomonster.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import com.photomonster.model.Monster
import com.photomonster.model.MonsterType
import com.photomonster.model.PhotoLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.lang.reflect.Type

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "game_save")

/**
 * ゲームデータをDataStoreに永続保存するリポジトリ
 */
class GameRepository(private val context: Context) {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Uri::class.java, UriSerializer())
        .registerTypeAdapter(LatLng::class.java, LatLngSerializer())
        .create()

    companion object {
        private val KEY_PHOTOS = stringPreferencesKey("photos")
        private val KEY_WILD_MONSTERS = stringPreferencesKey("wild_monsters")
        private val KEY_CAUGHT_MONSTERS = stringPreferencesKey("caught_monsters")
        private val KEY_CAPTURE_CUBES = intPreferencesKey("capture_cubes")
    }

    // ─── 読み込み ──────────────────────────────────────────────────────────────

    val photos: Flow<List<PhotoLocation>> = context.dataStore.data.map { prefs ->
        try {
            val json = prefs[KEY_PHOTOS] ?: return@map emptyList()
            val type = object : TypeToken<List<PhotoLocationDto>>() {}.type
            val dtos: List<PhotoLocationDto> = gson.fromJson(json, type) ?: emptyList()
            dtos.map { it.toPhotoLocation() }
        } catch (e: Exception) { emptyList() }
    }

    val wildMonsters: Flow<List<Monster>> = context.dataStore.data.map { prefs ->
        try {
            val json = prefs[KEY_WILD_MONSTERS] ?: return@map emptyList()
            val type = object : TypeToken<List<MonsterDto>>() {}.type
            val dtos: List<MonsterDto> = gson.fromJson(json, type) ?: emptyList()
            dtos.map { it.toMonster() }
        } catch (e: Exception) { emptyList() }
    }

    val caughtMonsters: Flow<List<Monster>> = context.dataStore.data.map { prefs ->
        try {
            val json = prefs[KEY_CAUGHT_MONSTERS] ?: return@map emptyList()
            val type = object : TypeToken<List<MonsterDto>>() {}.type
            val dtos: List<MonsterDto> = gson.fromJson(json, type) ?: emptyList()
            dtos.map { it.toMonster() }
        } catch (e: Exception) { emptyList() }
    }

    val captureCubes: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_CAPTURE_CUBES] ?: 0
    }

    // ─── 保存 ──────────────────────────────────────────────────────────────────

    suspend fun savePhotos(photos: List<PhotoLocation>) {
        try {
            val dtos = photos.map { PhotoLocationDto.from(it) }
            val json = gson.toJson(dtos)
            context.dataStore.edit { it[KEY_PHOTOS] = json }
        } catch (e: Exception) { /* save failure is non-fatal */ }
    }

    suspend fun saveWildMonsters(monsters: List<Monster>) {
        try {
            val dtos = monsters.map { MonsterDto.from(it) }
            val json = gson.toJson(dtos)
            context.dataStore.edit { it[KEY_WILD_MONSTERS] = json }
        } catch (e: Exception) { }
    }

    suspend fun saveCaughtMonsters(monsters: List<Monster>) {
        try {
            val dtos = monsters.map { MonsterDto.from(it) }
            val json = gson.toJson(dtos)
            context.dataStore.edit { it[KEY_CAUGHT_MONSTERS] = json }
        } catch (e: Exception) { }
    }

    suspend fun saveCaptureCubes(count: Int) {
        try {
            context.dataStore.edit { it[KEY_CAPTURE_CUBES] = count }
        } catch (e: Exception) { }
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    // ─── DTO（シリアライズ用の中間データクラス）────────────────────────────────────

    data class PhotoLocationDto(
        val id: Int,
        val uriString: String,
        val lat: Double,
        val lng: Double,
        val timestamp: String?,
        val address: String?,
        val lastCollectedTime: Long
    ) {
        fun toPhotoLocation(): PhotoLocation = PhotoLocation(
            id = id,
            uri = Uri.parse(uriString),
            latLng = LatLng(lat, lng),
            timestamp = timestamp,
            address = address,
            lastCollectedTime = lastCollectedTime
        )

        companion object {
            fun from(p: PhotoLocation) = PhotoLocationDto(
                id = p.id,
                uriString = p.uri.toString(),
                lat = p.latLng.latitude,
                lng = p.latLng.longitude,
                timestamp = p.timestamp,
                address = p.address,
                lastCollectedTime = p.lastCollectedTime
            )
        }
    }

    data class MonsterDto(
        val id: String,
        val name: String,
        val typeName: String,
        val lat: Double,
        val lng: Double,
        val hp: Int,
        val attack: Int,
        val defense: Int,
        val spawnTime: Long
    ) {
        fun toMonster(): Monster = Monster(
            id = id,
            name = name,
            type = MonsterType.valueOf(typeName),
            latLng = LatLng(lat, lng),
            hp = hp,
            attack = attack,
            defense = defense,
            spawnTime = spawnTime
        )

        companion object {
            fun from(m: Monster) = MonsterDto(
                id = m.id,
                name = m.name,
                typeName = m.type.name,
                lat = m.latLng.latitude,
                lng = m.latLng.longitude,
                hp = m.hp,
                attack = m.attack,
                defense = m.defense,
                spawnTime = m.spawnTime
            )
        }
    }

    // ─── Gson カスタムシリアライザー ──────────────────────────────────────────────

    private class UriSerializer : JsonSerializer<Uri>, JsonDeserializer<Uri> {
        override fun serialize(src: Uri?, typeOfSrc: Type?, ctx: JsonSerializationContext?) =
            com.google.gson.JsonPrimitive(src.toString())
        override fun deserialize(json: JsonElement?, typeOfT: Type?, ctx: JsonDeserializationContext?) =
            Uri.parse(json?.asString)
    }

    private class LatLngSerializer : JsonSerializer<LatLng>, JsonDeserializer<LatLng> {
        override fun serialize(src: LatLng?, typeOfSrc: Type?, ctx: JsonSerializationContext?) =
            JsonObject().apply {
                addProperty("lat", src?.latitude)
                addProperty("lng", src?.longitude)
            }
        override fun deserialize(json: JsonElement?, typeOfT: Type?, ctx: JsonDeserializationContext?): LatLng {
            val obj = json?.asJsonObject
            return LatLng(obj?.get("lat")?.asDouble ?: 0.0, obj?.get("lng")?.asDouble ?: 0.0)
        }
    }
}
