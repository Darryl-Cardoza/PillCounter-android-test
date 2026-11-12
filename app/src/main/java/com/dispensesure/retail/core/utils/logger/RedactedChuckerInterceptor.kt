package com.dispensesure.retail.core.utils.logger

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import okhttp3.Interceptor

/**
 * Chucker with confidential headers and fields hidden; used by the main and /health clients.
 * Typed as [Interceptor] so Hilt's generated Java never names Chucker's class (built for Java 21).
 */
fun redactedChuckerInterceptor(context: Context): Interceptor =
    ChuckerInterceptor.Builder(context)
        .redactHeaders(HttpLogRedaction.SENSITIVE_HEADERS)
        .addBodyDecoder(RedactingBodyDecoder)
        .build()
