package com.photomonster.model

import android.net.Uri
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.clustering.ClusterItem

/**
 * 位置情報付き写真のデータモデル
 * @param id          一意ID（URIのhashCode）
 * @param uri         写真の Content URI
 * @param latLng      GPS 座標 (latitude, longitude)
 * @param timestamp   EXIF の撮影日時文字列（例: "2025:01:15 14:30:00"）
 * @param address     逆ジオコーディングで取得した住所（取得前は null）
 * @param lastCollectedTime ゲーム仕様: アイテム最終回収時刻（Epoch ms）
 */
data class PhotoLocation(
    val id: Int,
    val uri: Uri,
    val latLng: LatLng,
    val timestamp: String?,
    val address: String? = null,
    val lastCollectedTime: Long = 0L
) : ClusterItem {
    override fun getPosition(): LatLng = latLng
    override fun getTitle(): String? = formattedTimestamp
    override fun getSnippet(): String? = address
    override fun getZIndex(): Float? = null

    /** 表示用の撮影日時（"2025:01:15 14:30:00" → "2025/01/15 14:30"） */
    val formattedTimestamp: String
        get() = timestamp
            ?.replaceFirst(":", "/")
            ?.replaceFirst(":", "/")
            ?.substringBeforeLast(":") ?: "撮影日時不明"

    /**
     * クールダウン（例: 5分）が経過し、再びアイテムが回収可能かどうか
     */
    val canCollectItems: Boolean
        get() = (System.currentTimeMillis() - lastCollectedTime) > (5 * 60 * 1000)
}
