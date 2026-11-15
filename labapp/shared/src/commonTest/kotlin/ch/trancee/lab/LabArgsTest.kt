package ch.trancee.lab

import ch.trancee.meshlink.internal.LinkRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LabArgsTest {
    @Test
    fun parsesEveryArgumentAndIgnoresTheExecutableAndFlags() {
        val tokens =
            listOf(
                "/app/LabHost",
                "-NSDoubleLocalizedStrings",
                "-AppleTextDirection=NO",
                "scenario=direct-message",
                "role=peripheral",
                "peer=ip15",
                "mesh=lab-7",
                "profile=unsecured",
                "run=r-1",
                "duration=90",
            )

        val args = LabArgs.parse(tokens)

        assertEquals("direct-message", args.scenario)
        assertEquals(LinkRole.PERIPHERAL, args.role)
        assertEquals("ip15", args.peer)
        assertEquals("lab-7", args.mesh)
        assertEquals(LabProfile.UNSECURED, args.profile)
        assertEquals("r-1", args.run)
        assertEquals(90.seconds, args.duration)
    }

    @Test
    fun defaultsToEitherRoleTheUnsecuredProfileAndSixtySeconds() {
        val args = LabArgs.parse(listOf("scenario=direct-message", "run=r"))

        assertNull(args.role)
        assertEquals(LabProfile.UNSECURED, args.profile)
        assertEquals(60.seconds, args.duration)
    }

    @Test
    fun classicalAndPostQuantumFailFastNamingTheTicketThatAddsThem() {
        for ((profile, ticket) in listOf("classical" to 24, "post-quantum" to 25)) {
            val tokens = listOf("scenario=direct-message", "run=r", "profile=$profile")

            val error = assertFailsWith<LabArgsException> { LabArgs.parse(tokens) }

            assertTrue("ticket $ticket" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun rejectsUnknownMissingOrInvalidArguments() {
        val invalid =
            listOf(
                listOf("scenario=direct-message", "run=r", "theme=dark"),
                listOf("scenario=direct-message"),
                listOf("scenario=direct-message", "run=../escape"),
                listOf("scenario=broadcast", "run=r"),
                listOf("scenario=direct-message", "run=r", "role=both"),
                listOf("scenario=direct-message", "run=r", "profile=none"),
                listOf("scenario=direct-message", "run=r", "duration=4"),
                listOf("scenario=direct-message", "run=r", "duration=soon"),
            )

        invalid.forEach { tokens -> assertFailsWith<LabArgsException>(tokens.toString()) { LabArgs.parse(tokens) } }
    }

    @Test
    fun runIdIsReadEvenWhenOtherArgumentsAreInvalid() {
        assertEquals("r", LabArgs.runId(listOf("run=r", "scenario=broadcast")))
        assertNull(LabArgs.runId(listOf("run=a/b")))
    }
}
