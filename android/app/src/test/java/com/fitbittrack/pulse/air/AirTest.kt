package com.fitbittrack.pulse.air

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.util.Locale

class AirTest {

    private lateinit var waqi: MockWebServer
    private lateinit var meteo: MockWebServer
    private val here = Place(12.9716, 77.5946)
    private var now = Instant.parse("2026-11-23T17:30:00Z")

    @Before fun setUp() {
        waqi = MockWebServer().apply { start() }
        meteo = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        waqi.shutdown()
        meteo.shutdown()
    }

    private fun client(key: String = "k") =
        AirClient(key, waqiBase = waqi.url("/").toString(), meteoBase = meteo.url("/").toString(), now = { now })

    private fun station(aqi: String = "162", lat: Double = 12.98, lon: Double = 77.60, time: String = "2026-11-23T22:30:00+05:30") =
        """{"status":"ok","data":{"aqi":$aqi,"attributions":[{"name":"State board"},{"name":"World Air Quality Index Project"}],
           "city":{"geo":[$lat,$lon],"name":"Test station"},"dominentpol":"pm25","time":{"iso":"$time"}}}"""

    private val estimate =
        """{"current":{"time":1795453200,"interval":3600,"us_aqi":96,"pm2_5":11.9,"pm10":12.2,"us_aqi_pm2_5":60,"us_aqi_ozone":96,"us_aqi_pm10":null}}"""

    private fun MockWebServer.reply(body: String, code: Int = 200) = enqueue(MockResponse().setResponseCode(code).setBody(body))

    // ---------- client ----------

    @Test fun usesAStationThatIsCloseAndRecent() = runTest {
        waqi.reply(station())
        val r = client().read(here)
        val s = r.source as AirSource.Station
        assertEquals(162, r.aqi)
        assertEquals(AirLevel.Unhealthy, r.level)
        assertEquals("Test station", s.name)
        assertEquals(1100.0, s.distanceMeters, 200.0)
        assertEquals("Fine dust (PM2.5)", r.mainPollutant)
        assertEquals(Instant.parse("2026-11-23T17:00:00Z"), r.at)
        assertEquals(0, meteo.requestCount)
        // Only a rounded position leaves the phone.
        val sent = waqi.takeRequest().requestUrl!!
        assertEquals("geo:12.97;77.59", sent.pathSegments[1])
        assertEquals("k", sent.queryParameter("token"))
    }

    @Test fun fallsBackToTheEstimateWhenAStationCannotBeTrusted() = runTest {
        val bad = listOf(
            station(lat = 13.2), // 25 km away
            station(time = "2026-11-23T18:00:00+05:30"), // five hours old
            station(time = "2026-11-24T18:00:00+05:30"), // in the future
            station(aqi = "\"-\""), station(aqi = "-1"), station(time = "soon"),
            """{"status":"error","data":"Unknown station"}""", """{"status":"nope"}""", "<html>", "[]", "",
        )
        bad.forEach { body ->
            waqi.reply(body)
            meteo.reply(estimate)
            val r = client().read(here)
            assertEquals(body, AirSource.Estimate, r.source)
            assertNull(body, r.note)
        }
        waqi.reply("", code = 500)
        meteo.reply(estimate)
        assertEquals(AirSource.Estimate, client().read(here).source)
        waqi.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        meteo.reply(estimate)
        assertEquals(AirSource.Estimate, client().read(here).source)
    }

    @Test fun aRefusedKeyIsExplained() = runTest {
        waqi.reply("""{"status":"error","data":"Invalid key"}""")
        meteo.reply(estimate)
        assertTrue("key" in client().read(here).note!!)
    }

    @Test fun withoutAKeyOnlyTheEstimateIsAsked() = runTest {
        meteo.reply(estimate)
        val r = client(key = "").read(here)
        assertEquals(96, r.aqi)
        assertEquals("Ozone", r.mainPollutant)
        assertEquals(11.9, r.pm25!!, 0.001)
        assertEquals(Instant.ofEpochSecond(1795453200), r.at)
        assertEquals(0, waqi.requestCount)
        val sent = meteo.takeRequest().requestUrl!!
        assertEquals("13.0", sent.queryParameter("latitude"))
        assertEquals("77.6", sent.queryParameter("longitude"))
    }

    @Test fun badEstimateRepliesAreCleanErrors() = runTest {
        listOf("""{"error":true,"reason":"x"}""", """{"current":{"time":1,"us_aqi":null}}""", "<html>", "").forEach { body ->
            meteo.reply(body)
            try {
                client(key = "").read(here)
                fail(body)
            } catch (e: AirException) {
                assertFalse("http" in e.message!!)
            }
        }
    }

    @Test fun positionsUseADotInEveryLanguage() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("12.97", AirClient.round(12.9716, 2))
            assertEquals("0.0", AirClient.round(-0.02, 1))
            assertEquals("-33.9", AirClient.round(-33.87, 1))
        } finally {
            Locale.setDefault(before)
        }
    }

    // ---------- rules ----------

    @Test fun levelsFollowTheUsIndex() {
        val expected = mapOf(
            0 to AirLevel.Good, 50 to AirLevel.Good, 51 to AirLevel.Moderate, 100 to AirLevel.Moderate, 101 to AirLevel.Sensitive,
            151 to AirLevel.Unhealthy, 200 to AirLevel.Unhealthy, 201 to AirLevel.VeryUnhealthy, 301 to AirLevel.Hazardous, 900 to AirLevel.Hazardous,
        )
        expected.forEach { (aqi, level) -> assertEquals("$aqi", level, AirLevel.of(aqi)) }
    }

    @Test fun distanceIsRight() {
        assertEquals(0.0, AirRules.meters(here, here), 0.001)
        assertEquals(344_000.0, AirRules.meters(Place(51.5074, -0.1278), Place(48.8566, 2.3522)), 2_000.0) // London to Paris
    }

    // ---------- engine ----------

    private val reading get() = AirReading(40, now, AirSource.Estimate)

    @Test fun asksAgainOnlyWhenOldOrForced() = runTest {
        var calls = 0
        val e = AirEngine({ Located.At(here) }, { calls++; reading }, { now })
        e.refresh(); e.refresh()
        assertEquals(1, calls)
        e.refresh(force = true)
        assertEquals(2, calls)
        now += Duration.ofMinutes(31)
        e.refresh()
        assertEquals(3, calls)
        assertFalse(e.state.value.loading)
    }

    @Test fun keepsTheLastReadingWhenARefreshFailsButNotForever() = runTest {
        var broken = false
        val e = AirEngine({ Located.At(here) }, { if (broken) throw AirException("No internet.") else reading }, { now })
        e.refresh()
        broken = true
        e.refresh(force = true)
        assertNotNull(e.state.value.reading)
        assertEquals(AirProblem.Service, e.state.value.problem)
        assertEquals("No internet.", e.state.value.message)
        now += Duration.ofHours(4)
        e.refresh()
        assertNull(e.state.value.reading)
        broken = false
        e.refresh() // a problem is retried at the next check, without force
        assertNull(e.state.value.problem)
        assertNotNull(e.state.value.reading)
    }

    @Test fun losingPermissionHidesTheReadingAndSendsNothing() = runTest {
        var located: Located = Located.At(here)
        var calls = 0
        val e = AirEngine({ located }, { calls++; reading }, { now })
        e.refresh()
        located = Located.NoPermission
        e.refresh(force = true)
        assertEquals(AirState(problem = AirProblem.NoPermission), e.state.value)
        assertEquals(1, calls)
        located = Located.SwitchedOff
        e.refresh()
        assertEquals(AirProblem.LocationOff, e.state.value.problem)
        located = Located.NotFound
        e.refresh()
        assertEquals(AirProblem.NoLocation, e.state.value.problem)
    }

    @Test fun leavingTheScreenMidWayStopsCleanly() = runTest {
        val never = CompletableDeferred<AirReading>()
        val e = AirEngine({ Located.At(here) }, { never.await() }, { now })
        val job = launch { e.refresh() }
        runCurrent()
        assertTrue(e.state.value.loading)
        job.cancel()
        e.clear()
        runCurrent()
        assertEquals(AirState(), e.state.value)
    }
}
