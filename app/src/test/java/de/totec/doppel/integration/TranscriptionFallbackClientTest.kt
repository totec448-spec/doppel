package de.totec.doppel.integration

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionFallbackClientTest {
    @Test
    fun `sends authenticated multipart only on demand and parses bounded text`() = runBlocking {
        val apiKey = "example-" + "transcription-key"
        var authorization: String? = null
        var encodedBody = ""
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    authorization = request.header("Authorization")
                    encodedBody =
                        Buffer().also { request.body?.writeTo(it) }.readUtf8()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"text":"  hallo aus dem fallback  "}"""
                                .toResponseBody("application/json".toMediaType()),
                        )
                        .build()
                }
                .build()
        val client =
            OkHttpTranscriptionFallbackClient(http) {
                TranscriptionFallbackConfiguration(
                    endpointUrl = "https://stt.example/v1/audio/transcriptions",
                    model = "whisper-test",
                    apiKey = apiKey,
                )
            }

        val result =
            client.transcribe(
                bytes = "audio-data".toByteArray(),
                mimeType = "audio/ogg; codecs=opus",
                fileName = "../../voice note.ogg",
            )

        assertEquals("hallo aus dem fallback", result)
        assertEquals("Bearer $apiKey", authorization)
        assertTrue(encodedBody.contains("whisper-test"))
        assertTrue(encodedBody.contains("voice_note.ogg"))
        assertTrue(encodedBody.contains("audio-data"))
    }

    @Test
    fun `rejects cleartext endpoint before opening a connection`() {
        val client =
            OkHttpTranscriptionFallbackClient(OkHttpClient()) {
                TranscriptionFallbackConfiguration(
                    endpointUrl = "http://stt.example/transcriptions",
                    model = "whisper-test",
                    apiKey = "example-transcription-key",
                )
            }

        val error =
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    client.transcribe(
                        bytes = byteArrayOf(1),
                        mimeType = "audio/ogg",
                        fileName = null,
                    )
                }
            }
        assertEquals("Transcription endpoint must use HTTPS", error.message)
    }

    @Test
    fun `provider failures expose only a stable reason code`() {
        val http =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(429)
                        .message("Too Many Requests")
                        .body(
                            """{"error":"credential and provider detail must stay private"}"""
                                .toResponseBody("application/json".toMediaType()),
                        )
                        .build()
                }
                .build()
        val client =
            OkHttpTranscriptionFallbackClient(http) {
                TranscriptionFallbackConfiguration(
                    endpointUrl = "https://stt.example/transcriptions",
                    model = "whisper-test",
                    apiKey = "example-transcription-key",
                )
            }

        val error =
            assertThrows(TranscriptionFallbackException::class.java) {
                runBlocking {
                    client.transcribe(
                        bytes = byteArrayOf(1, 2, 3),
                        mimeType = "audio/mpeg",
                        fileName = "voice.mp3",
                    )
                }
            }
        assertEquals("stt_http_429", error.message)
    }
}
