package com.example.twopchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorCircuitParserTest {

    @Test
    fun testParseStandardThreeHopCircuit() {
        val line = "1 BUILT \$A1B2C3D4E5F6789012345678901234567890ABCD~GuardDE,\$B1B2C3D4E5F6789012345678901234567890ABCD~RelayNL,\$C1B2C3D4E5F6789012345678901234567890ABCD~ExitUS PURPOSE=GENERAL"
        val nodes = TorManager.parseCircuitStatusNodes(line)

        assertEquals(3, nodes.size)
        assertEquals("Guard", nodes[0].role)
        assertEquals("GuardDE", nodes[0].name)
        assertEquals("DE", nodes[0].countryCode)
        assertEquals("🇩🇪", nodes[0].flagEmoji)

        assertEquals("Middle", nodes[1].role)
        assertEquals("RelayNL", nodes[1].name)
        assertEquals("NL", nodes[1].countryCode)
        assertEquals("🇳🇱", nodes[1].flagEmoji)

        assertEquals("Exit", nodes[2].role)
        assertEquals("ExitUS", nodes[2].name)
        assertEquals("US", nodes[2].countryCode)
        assertEquals("🇺🇸", nodes[2].flagEmoji)
    }

    @Test
    fun testParseCircuitWithEqualSeparator() {
        val line = "2 BUILT \$1111111111111111111111111111111111111111=NodeSE,\$2222222222222222222222222222222222222222=NodeCH PURPOSE=GENERAL"
        val nodes = TorManager.parseCircuitStatusNodes(line)

        assertEquals(3, nodes.size)
        assertEquals("Guard", nodes[0].role)
        assertEquals("NodeSE", nodes[0].name)
        assertEquals("SE", nodes[0].countryCode)
        assertEquals("🇸🇪", nodes[0].flagEmoji)

        assertEquals("Middle", nodes[1].role)
        assertEquals("NodeCH", nodes[1].name)
        assertEquals("CH", nodes[1].countryCode)
        assertEquals("🇨🇭", nodes[1].flagEmoji)

        // Padded 3rd node fallback
        assertEquals("Exit", nodes[2].role)
        assertEquals("Exit", nodes[2].name)
        assertEquals("🌐", nodes[2].flagEmoji)
    }

    @Test
    fun testParseOneHopIntroductoryCircuit() {
        val line = "3 BUILT \$9999999999999999999999999999999999999999~IntroJP PURPOSE=HS_INTRO"
        val nodes = TorManager.parseCircuitStatusNodes(line)

        assertEquals(3, nodes.size)
        assertEquals("Guard", nodes[0].role)
        assertEquals("IntroJP", nodes[0].name)
        assertEquals("JP", nodes[0].countryCode)
        assertEquals("🇯🇵", nodes[0].flagEmoji)

        assertEquals("Middle", nodes[1].role)
        assertEquals("Middle", nodes[1].name)
        assertEquals("🌐", nodes[1].flagEmoji)

        assertEquals("Exit", nodes[2].role)
        assertEquals("Exit", nodes[2].name)
        assertEquals("🌐", nodes[2].flagEmoji)
    }

    @Test
    fun testParseEmptyOrMalformedCircuitResponse() {
        val emptyNodes = TorManager.parseCircuitStatusNodes("")
        assertEquals(3, emptyNodes.size)
        assertEquals("Guard", emptyNodes[0].role)
        assertEquals("Middle", emptyNodes[1].role)
        assertEquals("Exit", emptyNodes[2].role)

        val malformedNodes = TorManager.parseCircuitStatusNodes("250 OK\r\n")
        assertEquals(3, malformedNodes.size)
        assertEquals("Guard", malformedNodes[0].role)
        assertEquals("Middle", malformedNodes[1].role)
        assertEquals("Exit", malformedNodes[2].role)
    }

    @Test
    fun testCountryFlagEmojiMapping() {
        assertEquals("🇩🇪", TorManager.countryCodeToFlagEmoji("DE"))
        assertEquals("🇫🇷", TorManager.countryCodeToFlagEmoji("FR"))
        assertEquals("🇺🇸", TorManager.countryCodeToFlagEmoji("US"))
        assertEquals("🇷🇺", TorManager.countryCodeToFlagEmoji("RU"))
        assertEquals("🇬🇧", TorManager.countryCodeToFlagEmoji("GB"))
        assertEquals("🇨🇦", TorManager.countryCodeToFlagEmoji("CA"))
        assertEquals("🇯🇵", TorManager.countryCodeToFlagEmoji("JP"))
        assertEquals("🌐", TorManager.countryCodeToFlagEmoji(null))
        assertEquals("🌐", TorManager.countryCodeToFlagEmoji(""))
        assertEquals("🌐", TorManager.countryCodeToFlagEmoji("123"))
    }
}
