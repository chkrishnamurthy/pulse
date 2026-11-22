package com.fitbittrack.pulse.data

import com.fitbittrack.pulse.data.remote.HealthClient
import com.fitbittrack.pulse.data.remote.TokenProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class HealthApiTest {

    private lateinit var server: MockWebServer
    private val waits = mutableListOf<Long>()
    private lateinit var api: HealthApi

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = HealthApi(baseUrl = server.url("/").toString(), sleeper = { waits += it })
    }

    @After fun tearDown() = server.shutdown()

    private fun respond(code: Int, body: String) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test fun retriesRateLimitThenSucceeds() = runTest {
        respond(429, """{"error":{"code":429}}""")
        respond(503, "")
        respond(200, """{"healthUserId":"x"}""")
        val r = api.getIdentity("t")
        assertTrue(r is HealthApi.Result.Ok && r.value.hasHealthUserId)
        assertEquals(listOf(1000L, 2000L), waits)
        assertEquals("Bearer t", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun accountNotLinkedIsExplained() = runTest {
        respond(400, """{"error":{"status":"FAILED_PRECONDITION","details":[{"reason":"ACCOUNT_NOT_LINKED"}]}}""")
        val r = api.getIdentity("t") as HealthApi.Result.Error
        assertEquals(400, r.httpCode)
        assertTrue("not linked" in r.message)
        assertEquals(0, waits.size) // 400 is not retried
    }

    @Test fun listFollowsPagesAndRefreshesTokenOn401() = runTest {
        val tokens = object : TokenProvider {
            var n = 0
            override suspend fun accessToken() = "tok${n}"
            override fun invalidate() { n++ }
        }
        val client = HealthClient(api, tokens)
        respond(401, "{}")
        respond(200, """{"dataPoints":[{"name":"1"},{"name":"2"}],"nextPageToken":"p2"}""")
        respond(200, """{"dataPoints":[{"name":"3"}],"nextPageToken":""}""")

        val points = client.list("sleep", filter = "sleep.interval.civil_end_time >= \"2026-11-01\"", pageSize = 25)
        assertEquals(3, points.size)

        val first = server.takeRequest()
        assertEquals("Bearer tok0", first.getHeader("Authorization"))
        val second = server.takeRequest()
        assertEquals("Bearer tok1", second.getHeader("Authorization"))
        assertTrue(second.path!!.startsWith("/v4/users/me/dataTypes/sleep/dataPoints?"))
        assertEquals("sleep.interval.civil_end_time >= \"2026-11-01\"", second.requestUrl!!.queryParameter("filter"))
        assertEquals("25", second.requestUrl!!.queryParameter("pageSize"))
        assertEquals("p2", server.takeRequest().requestUrl!!.queryParameter("pageToken"))
    }

    @Test fun dailyRollUpSendsCivilRange() = runTest {
        val client = HealthClient(api, object : TokenProvider {
            override suspend fun accessToken() = "t"
            override fun invalidate() {}
        })
        respond(200, """{"rollupDataPoints":[{"civilStartTime":{"date":{"year":2026,"month":11,"day":15}},"steps":{"countSum":"8430"}}]}""")
        val pts = client.dailyRollUp("steps", LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 22), "steps")
        assertEquals(LocalDate.of(2026, 11, 15), pts.single().civilDate)
        val req = server.takeRequest()
        assertEquals("/v4/users/me/dataTypes/steps/dataPoints:dailyRollUp", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"windowSizeDays\":1"))
        assertTrue(body.contains("\"day\":22"))
    }

    @Test fun givesUpAfterMaxAttempts() = runTest {
        repeat(5) { respond(500, "") }
        try {
            api.get("t", "v4/users/me/identity")
            fail("should throw")
        } catch (e: HealthApiException) {
            assertEquals(500, e.httpCode)
            assertEquals(4, waits.size)
        }
    }
}
