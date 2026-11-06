package com.dispensesure.retail.core.audit.key

import com.dispensesure.retail.BuildConfig
import com.dispensesure.retail.core.audit.AuditLogConfig
import com.dispensesure.retail.core.room.models.DekVersionEntity
import com.dispensesure.retail.core.utils.constants.URLConstant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/** JSON body of POST /mobile/devices/keys (guide §1.5). `dek` is the row's PCWRP bytes in Base64. */
@Serializable
data class DeviceKeyRequest(
    @SerialName("device_key") val deviceKey: String,
    @SerialName("dek_key_id") val dekKeyId: String,
    @SerialName("version") val version: Int,
    @SerialName("dek") val dek: String,
    @SerialName("kek_key_id") val kekKeyId: String,
    @SerialName("kek_version") val kekVersion: Int,
    @SerialName("algorithm") val algorithm: String,
    @SerialName("key_length_bits") val keyLengthBits: Int,
    @SerialName("platform") val platform: String,
    @SerialName("app_version") val appVersion: String
) {
    fun toJsonBody(): RequestBody =
        Json.encodeToString(serializer(), this).toRequestBody(URLConstant.CONTENT_TYPE.toMediaType())

    companion object {
        fun of(rowToSend: DekVersionEntity, version: Int, deviceKey: String) = DeviceKeyRequest(
            deviceKey = deviceKey,
            dekKeyId = rowToSend.dekKeyId,
            version = version,
            dek = Base64.getEncoder().encodeToString(rowToSend.wrappedDek),
            kekKeyId = rowToSend.kekKeyId,
            kekVersion = rowToSend.kekVersion,
            algorithm = AuditLogConfig.DEK_ALGORITHM,
            keyLengthBits = AuditLogConfig.DEK_KEY_LENGTH_BITS,
            platform = AuditLogConfig.PLATFORM,
            appVersion = BuildConfig.VERSION_NAME
        )
    }
}
