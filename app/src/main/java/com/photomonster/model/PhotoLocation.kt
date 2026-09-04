package com.photomonster.model

import android.net.Uri
import com.google.android.gms.maps.model.LatLng

/**
 * 位置情報付き写真のデータモデル
 * ※ClusterItem は不要になったため削除（独自クラスタリング実装に変更）
 *
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
) {
    /** 表示用の撮影日時 */
    val formattedTimestamp: String
        get() = timestamp
            ?.replaceFirst(":", "/")
            ?.replaceFirst(":", "/")
            ?.substringBeforeLast(":") ?: "撮影日時不明"

    /** クールダウン（5分）が経過し、再びアイテムが回収可能かどうか */
    val canCollectItems: Boolean
        get() = (System.currentTimeMillis() - lastCollectedTime) > (5 * 60 * 1000)
}

/**
 * 半径200m以内の写真をグループ化したスポット
 * マップ上には1つのピンとして表示される
 */
data class PhotoSpot(
    val id: String,
    val centerLatLng: LatLng,
    val photos: List<PhotoLocation>
) {
    /** スポットを代表する写真（最初の1枚） */
    val representativePhoto: PhotoLocation get() = photos.first()

    /** スポット内の任意の写真でアイテム回収が可能か */
    val canCollectItems: Boolean get() = photos.any { it.canCollectItems }
}
