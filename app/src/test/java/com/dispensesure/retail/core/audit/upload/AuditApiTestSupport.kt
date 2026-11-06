package com.dispensesure.retail.core.audit.upload

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import retrofit2.Response

private val PART_NAME = Regex("""name="([^"]+)"""")

/** A response in the backend envelope; [extraData] goes inside `data`, e.g. `,"next_free_sequence":4`. */
fun reply(status: Int, code: String?, extraData: String = ""): Response<ResponseBody> {
    val codeJson = code?.let { "\"$it\"" } ?: "null"
    val body = """{"status":$status,"is_success":${status < 300},"message":"","token":null,"data":{"code":$codeJson$extraData}}"""
        .toResponseBody()
    return if (status in 200..299) Response.success(status, body) else Response.error(status, body)
}

/** Text parts by name; the file part is left out. */
fun MultipartBody.textParts(): Map<String, String> =
    parts.filterNot { it.isFilePart() }
        .associate { part -> part.partName() to Buffer().also { part.body.writeTo(it) }.readUtf8() }

fun MultipartBody.filePart(): MultipartBody.Part = parts.single { it.isFilePart() }

private fun MultipartBody.Part.disposition(): String = headers?.get("Content-Disposition").orEmpty()
private fun MultipartBody.Part.isFilePart(): Boolean = disposition().contains("filename=")
private fun MultipartBody.Part.partName(): String = PART_NAME.find(disposition())!!.groupValues[1]
