package com.dispensesure.retail.core.utils.logger

import com.chuckerteam.chucker.api.BodyDecoder
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.Response
import okio.ByteString

/** Shows Chucker's JSON and text bodies with [HttpLogRedaction] applied; anything else goes to Chucker's own decoder. */
object RedactingBodyDecoder : BodyDecoder {
    override fun decodeRequest(request: Request, body: ByteString): String? =
        redactIfText(request.body?.contentType(), body)

    override fun decodeResponse(response: Response, body: ByteString): String? =
        redactIfText(response.header("Content-Type")?.toMediaTypeOrNull(), body)

    private fun redactIfText(contentType: MediaType?, body: ByteString): String? =
        if (contentType?.subtype == "json" || contentType?.type == "text") HttpLogRedaction.redact(body.utf8()) else null
}
