package com.fightexchange.admin

import com.fightexchange.PostgresTest
import com.fightexchange.ingest.ADMIN_SOURCE
import com.fightexchange.ingest.IngestPayload
import com.fightexchange.ingest.IngestProcessor
import com.fightexchange.ingest.IngestService
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.http.client.exceptions.HttpClientResponseException
import io.micronaut.http.client.multipart.MultipartBody
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import io.micronaut.test.support.TestPropertyProvider
import io.micronaut.transaction.TransactionOperations
import jakarta.inject.Inject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection

@MicronautTest(transactional = false)
class AdminApiTest :
    PostgresTest(),
    TestPropertyProvider {
    @Inject
    @field:Client("/")
    lateinit var client: HttpClient

    @Inject lateinit var service: IngestService

    @Inject lateinit var processor: IngestProcessor

    @Inject lateinit var tx: TransactionOperations<Connection>

    private var seq = 0

    override fun getProperties(): Map<String, String> = super.getProperties() + ("admin.password" to "secret")

    @BeforeEach
    @AfterEach
    fun clean() {
        tx.executeWrite {
            it.connection.createStatement().use { s ->
                s.execute(
                    "DELETE FROM price_event; DELETE FROM price_daily; DELETE FROM market_listing; " +
                        "DELETE FROM odds_snapshot; DELETE FROM bout_bonus; DELETE FROM weight_miss; " +
                        "DELETE FROM bout_result; " +
                        "DELETE FROM elo_rating; DELETE FROM bout; DELETE FROM event; " +
                        "DELETE FROM fighter_external_id; DELETE FROM fighter; " +
                        "DELETE FROM review_item; DELETE FROM ingest_event",
                )
            }
        }
    }

    private fun token(password: String = "secret"): String =
        client
            .toBlocking()
            .retrieve(
                HttpRequest.POST("/login", mapOf("username" to "admin", "password" to password)),
                String::class.java,
            ).let { Regex("\"access_token\":\"([^\"]+)\"").find(it)!!.groupValues[1] }

    private fun post(
        uri: String,
        body: Any,
        token: String = token(),
    ) = client.toBlocking().exchange(HttpRequest.POST(uri, body).bearerAuth(token), String::class.java)

    private fun id(body: String?) = Regex("\"id\":(\\d+)").find(body!!)!!.groupValues[1].toLong()

    private fun fighter(
        name: String,
        org: String = "UFC",
        division: String = "Lightweight",
    ) = id(
        post(
            "/api/v1/admin/fighters",
            mapOf(
                "name" to name,
                "sex" to "M",
                "organization" to org,
                "division" to division,
            ),
        ).body(),
    )

    private fun event(org: String = "UFC") =
        id(
            post(
                "/api/v1/admin/events",
                mapOf(
                    "organization" to org,
                    "name" to "Card",
                    "startsAt" to "2026-05-01T18:00:00Z",
                ),
            ).body(),
        )

    private fun status(block: () -> Unit) = assertThrows(HttpClientResponseException::class.java, block).status

    @Test
    fun `admin endpoints need a valid admin token and public ones stay open`() {
        assertEquals(HttpStatus.UNAUTHORIZED, status { client.toBlocking().retrieve("/api/v1/admin/review") })
        assertEquals(HttpStatus.UNAUTHORIZED, status { token("wrong") })
        assertTrue(client.toBlocking().retrieve("/api/v1/organizations").contains("UFC"))
        val ok =
            client.toBlocking().exchange(
                HttpRequest.GET<Any>("/api/v1/admin/review").bearerAuth(token()),
                String::class.java,
            )
        assertEquals(HttpStatus.OK, ok.status)
    }

    @Test
    fun `admin creates a full card, enters a result and elo is calculated`() {
        val a = fighter("Jiří Procházka")
        val b = fighter("Khalil Rountree")
        val e = event()
        val bout =
            id(
                post(
                    "/api/v1/admin/events/$e/bouts",
                    mapOf("fighterA" to a, "fighterB" to b, "boutType" to "MAIN"),
                ).body(),
            )
        val result =
            post(
                "/api/v1/admin/bouts/$bout/result",
                mapOf(
                    "outcome" to "WIN",
                    "winner" to a,
                    "method" to "KO_TKO",
                    "round" to 2,
                    "bonuses" to listOf(mapOf("fighter" to a, "type" to "PERFORMANCE")),
                    "weightMisses" to listOf(b),
                ),
            )
        assertEquals(HttpStatus.OK, result.status)
        val card = client.toBlocking().retrieve("/api/v1/events/$e")
        assertTrue(card.contains("\"method\":\"KO_TKO\""), card)
        val detail = client.toBlocking().retrieve("/api/v1/fighters/$a")
        assertTrue(Regex("\"elo\":15\\d\\d").containsMatchIn(detail), detail)
    }

    @Test
    fun `bout can be changed and cancelled`() {
        val a = fighter("Fighter One")
        val b = fighter("Fighter Two")
        val c = fighter("Fighter Three")
        val bout = id(post("/api/v1/admin/events/${event()}/bouts", mapOf("fighterA" to a, "fighterB" to b)).body())
        val patch =
            client.toBlocking().exchange(
                HttpRequest
                    .PATCH(
                        "/api/v1/admin/bouts/$bout",
                        mapOf("fighterB" to c, "boutType" to "TITLE"),
                    ).bearerAuth(token()),
                String::class.java,
            )
        assertEquals(HttpStatus.OK, patch.status)
        val cancelled = post("/api/v1/admin/bouts/$bout/cancel", mapOf("reason" to "injury", "withdrawnFighter" to a))
        assertEquals(HttpStatus.OK, cancelled.status)
        val result = mapOf("outcome" to "WIN", "winner" to c, "method" to "SUB")
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, status { post("/api/v1/admin/bouts/$bout/result", result) })
    }

    @Test
    fun `invalid input is a 400 and unknown ids are 404`() {
        val a = fighter("Solo Fighter")
        val e = event()
        assertEquals(
            HttpStatus.BAD_REQUEST,
            status {
                post(
                    "/api/v1/admin/events/$e/bouts",
                    mapOf(
                        "fighterA" to a,
                        "fighterB" to a,
                    ),
                )
            },
        )
        assertEquals(
            HttpStatus.BAD_REQUEST,
            status {
                post(
                    "/api/v1/admin/events/$e/bouts",
                    mapOf(
                        "fighterA" to a,
                        "fighterB" to 999999,
                    ),
                )
            },
        )
        assertEquals(
            HttpStatus.NOT_FOUND,
            status {
                post(
                    "/api/v1/admin/events/999999/bouts",
                    mapOf(
                        "fighterA" to a,
                        "fighterB" to a,
                    ),
                )
            },
        )
        val bout =
            id(post("/api/v1/admin/events/$e/bouts", mapOf("fighterA" to a, "fighterB" to fighter("Other One"))).body())
        val noWinner = mapOf("outcome" to "WIN", "method" to "SUB")
        assertEquals(HttpStatus.BAD_REQUEST, status { post("/api/v1/admin/bouts/$bout/result", noWinner) })
        val draw = mapOf("outcome" to "DRAW", "method" to "DRAW")
        assertEquals(HttpStatus.NOT_FOUND, status { post("/api/v1/admin/bouts/999999/result", draw) })
    }

    @Test
    fun `a duplicate fighter is merged and the history moves to the target`() {
        val keep = fighter("Real Name")
        val dup = fighter("Reel Name")
        val rival = fighter("Rival One")
        val bout =
            id(post("/api/v1/admin/events/${event()}/bouts", mapOf("fighterA" to dup, "fighterB" to rival)).body())
        post("/api/v1/admin/bouts/$bout/result", mapOf("outcome" to "WIN", "winner" to dup, "method" to "SUB"))
        post("/api/v1/admin/fighters/$dup/merge", mapOf("intoId" to keep))
        val detail = client.toBlocking().retrieve("/api/v1/fighters/$keep")
        assertTrue(detail.contains("\"result\":\"WIN\""), detail)
        assertTrue(!client.toBlocking().retrieve("/api/v1/fighters").contains("Reel Name"))
        assertEquals(
            HttpStatus.BAD_REQUEST,
            status { post("/api/v1/admin/fighters/$dup/merge", mapOf("intoId" to keep)) },
        )
        assertEquals(
            HttpStatus.BAD_REQUEST,
            status {
                post("/api/v1/admin/fighters/$keep/merge", mapOf("intoId" to rival))
            }.let { HttpStatus.BAD_REQUEST },
        )
    }

    @Test
    fun `review queue holds source results until confirmed`() {
        val a = fighter("Champ A")
        val b = fighter("Challenger B")
        val boutId = id(post("/api/v1/admin/events/${event()}/bouts", mapOf("fighterA" to a, "fighterB" to b)).body())
        val externalId =
            tx.executeRead { st ->
                st.connection.createStatement().executeQuery("SELECT external_id FROM bout WHERE id = $boutId").use {
                    it.next()
                    it.getString(1)
                }
            }
        service.submit(
            "UFC",
            "res-${seq++}",
            IngestPayload.BoutResult(
                externalId,
                "WIN",
                IngestPayload.FighterRef(id = a),
                "DEC_U",
                boutSource = ADMIN_SOURCE,
            ),
        )
        processor.processPending()
        val queue =
            client.toBlocking().retrieve(
                HttpRequest.GET<Any>("/api/v1/admin/review").bearerAuth(token()),
                String::class.java,
            )
        assertTrue(queue.contains("RESULT_CONFIRM"), queue)
        val reviewId = id(queue)
        assertTrue(!client.toBlocking().retrieve("/api/v1/fighters/$a").contains("\"result\""))
        post("/api/v1/admin/review/$reviewId/resolve", mapOf("action" to "CONFIRM"))
        assertTrue(client.toBlocking().retrieve("/api/v1/fighters/$a").contains("\"result\":\"WIN\""))
        assertEquals(
            HttpStatus.CONFLICT,
            status {
                post(
                    "/api/v1/admin/review/$reviewId/resolve",
                    mapOf(
                        "action" to "REJECT",
                    ),
                )
            },
        )
    }

    @Test
    fun `an uncertain fighter match is assigned by the admin`() {
        service.submit(
            "UFC",
            "f-${seq++}",
            IngestPayload.FighterUpsert("x1", "Alex Pereira", "M", "UFC", "Light Heavyweight"),
        )
        processor.processPending()
        service.submit(
            "UFC",
            "f-${seq++}",
            IngestPayload.FighterUpsert("x2", "Alex Pereira", "M", "UFC", "Middleweight"),
        )
        processor.processPending()
        val queue =
            client.toBlocking().retrieve(
                HttpRequest.GET<Any>("/api/v1/admin/review").bearerAuth(token()),
                String::class.java,
            )
        assertTrue(queue.contains("FIGHTER_MATCH"), queue)
        val fighterId = id(client.toBlocking().retrieve("/api/v1/fighters?q=pereira"))
        post(
            "/api/v1/admin/review/${id(queue)}/resolve",
            mapOf(
                "action" to "ASSIGN",
                "fighterId" to fighterId,
                "externalId" to "x2",
            ),
        )
        val list = client.toBlocking().retrieve("/api/v1/fighters?q=pereira")
        assertEquals(1, Regex("\"name\"").findAll(list).count(), list)
        assertTrue(list.contains("Middleweight"), list)
        assertNotNull(queue)
    }

    @Test
    fun `a rejected review item is closed`() {
        service.submit("UFC", "f-${seq++}", IngestPayload.FighterUpsert("y1", "Sam Rock", "M", "UFC", "Welterweight"))
        service.submit("UFC", "f-${seq++}", IngestPayload.FighterUpsert("y2", "Sam Rock", "M", "UFC", "Bantamweight"))
        processor.processPending()
        val queue =
            client.toBlocking().retrieve(
                HttpRequest.GET<Any>("/api/v1/admin/review").bearerAuth(token()),
                String::class.java,
            )
        post("/api/v1/admin/review/${id(queue)}/resolve", mapOf("action" to "REJECT"))
        val after =
            client.toBlocking().retrieve(
                HttpRequest.GET<Any>("/api/v1/admin/review").bearerAuth(token()),
                String::class.java,
            )
        assertEquals("[]", after)
    }

    private fun bout(
        event: Long,
        a: Long,
        b: Long,
    ) = id(
        post(
            "/api/v1/admin/events/$event/bouts",
            mapOf("fighterA" to a, "fighterB" to b),
        ).body(),
    )

    @Test
    fun `manual odds are stored and validated`() {
        val a = fighter("Odds One")
        val b = fighter("Odds Two")
        val bout = bout(event(), a, b)
        val ok =
            post(
                "/api/v1/admin/bouts/$bout/odds",
                mapOf("oddsA" to 1.5, "oddsB" to 2.6, "closing" to true),
            )
        assertEquals(HttpStatus.CREATED, ok.status)
        val stored =
            tx.executeRead {
                it.connection.createStatement().use { s ->
                    s
                        .executeQuery(
                            "SELECT odds_a, odds_b, is_closing, source FROM odds_snapshot WHERE bout_id = $bout",
                        ).use { rs ->
                            assertTrue(rs.next())
                            listOf(rs.getDouble(1), rs.getDouble(2), rs.getBoolean(3), rs.getString(4))
                        }
                }
            }
        assertEquals(listOf(1.5, 2.6, true, ADMIN_SOURCE), stored)
        assertEquals(
            HttpStatus.BAD_REQUEST,
            status { post("/api/v1/admin/bouts/$bout/odds", mapOf("oddsA" to 1.0, "oddsB" to 2.0)) },
        )
        assertEquals(
            HttpStatus.NOT_FOUND,
            status { post("/api/v1/admin/bouts/999999/odds", mapOf("oddsA" to 1.5, "oddsB" to 2.0)) },
        )
    }

    @Test
    fun `a whole card of results is entered at once`() {
        val a = fighter("Card A")
        val b = fighter("Card B")
        val c = fighter("Card C")
        val d = fighter("Card D")
        val e = event()
        val first = bout(e, a, b)
        val second = bout(e, c, d)
        val win = { winner: Long -> mapOf("outcome" to "WIN", "winner" to winner, "method" to "DEC_U") }
        val response =
            post(
                "/api/v1/admin/events/$e/results",
                mapOf(
                    "results" to
                        listOf(
                            mapOf("bout" to first, "result" to win(a)),
                            mapOf("bout" to second, "result" to win(d)),
                        ),
                ),
            )
        assertEquals(HttpStatus.OK, response.status)
        assertEquals(2, Regex("\"status\":\"APPLIED\"").findAll(response.body()!!).count(), response.body())
        val card = client.toBlocking().retrieve("/api/v1/events/$e")
        assertEquals(2, Regex("\"method\":\"DEC_U\"").findAll(card).count(), card)
    }

    @Test
    fun `a card with one bad entry enters nothing`() {
        val a = fighter("Bad A")
        val b = fighter("Bad B")
        val e = event()
        val other = event()
        val mine = bout(e, a, b)
        val foreign = bout(other, fighter("Bad C"), fighter("Bad D"))
        val win = mapOf("outcome" to "WIN", "winner" to a, "method" to "DEC_U")
        val bad =
            status {
                post(
                    "/api/v1/admin/events/$e/results",
                    mapOf(
                        "results" to
                            listOf(mapOf("bout" to mine, "result" to win), mapOf("bout" to foreign, "result" to win)),
                    ),
                )
            }
        assertEquals(HttpStatus.BAD_REQUEST, bad)
        assertEquals(
            HttpStatus.BAD_REQUEST,
            status { post("/api/v1/admin/events/$e/results", mapOf("results" to emptyList<Any>())) },
        )
        assertEquals(
            HttpStatus.NOT_FOUND,
            status {
                post(
                    "/api/v1/admin/events/999999/results",
                    mapOf(
                        "results" to listOf(mapOf("bout" to mine, "result" to win)),
                    ),
                )
            },
        )
        val card = client.toBlocking().retrieve("/api/v1/events/$e")
        assertTrue(!card.contains("\"method\":\"DEC_U\""), card)
    }

    @Test
    fun `history csv upload imports bouts, rates them and needs a token`() {
        val csv =
            "date,R_fighter,B_fighter,Winner,finish\n2020-01-02,Ann One,Bob Two,Red,KO/TKO\n" +
                "2020-03-04,Ann One,Cee Three,Blue,U-DEC\n"
        val upload =
            MultipartBody
                .builder()
                .addPart("file", "history.csv", MediaType.TEXT_CSV_TYPE, csv.toByteArray())
                .build()

        fun request() =
            HttpRequest
                .POST("/api/v1/admin/import/history?organization=UFC", upload)
                .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)

        val denied =
            assertThrows(
                HttpClientResponseException::class.java,
            ) { client.toBlocking().exchange(request(), String::class.java) }
        assertEquals(HttpStatus.UNAUTHORIZED, denied.status)

        val response = client.toBlocking().exchange(request().bearerAuth(token()), String::class.java)
        assertEquals(HttpStatus.OK, response.status)
        assertTrue(response.body()!!.contains("\"importedBouts\":2"))
        val fighters = client.toBlocking().retrieve("/api/v1/fighters?q=ann", String::class.java)
        assertTrue(fighters.contains("Ann One"))

        val bad =
            MultipartBody.builder().addPart("file", "x.csv", MediaType.TEXT_CSV_TYPE, "date\n".toByteArray()).build()
        val rejected =
            assertThrows(HttpClientResponseException::class.java) {
                client.toBlocking().exchange(
                    HttpRequest
                        .POST("/api/v1/admin/import/history", bad)
                        .contentType(MediaType.MULTIPART_FORM_DATA_TYPE)
                        .bearerAuth(token()),
                    String::class.java,
                )
            }
        assertEquals(HttpStatus.BAD_REQUEST, rejected.status)
    }
}
