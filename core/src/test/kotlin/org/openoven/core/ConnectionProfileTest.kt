package org.openoven.core
import org.junit.Assert.*
import org.junit.Test

class ConnectionProfileTest {
    @Test fun roundtripPreservesIdentityAndUnicodeWithoutPhoneOrWifiSecrets() {
        val profile=ConnectionProfile("Kuchnia — góra","A+&=?abc12345678",ovenAddress="192.0.2.105")
        // The wire identity must be exactly sixteen ASCII characters, including escaped punctuation.
        val valid=profile
        val encoded=ConnectionProfileCodec.encode(valid)
        assertEquals(valid,ConnectionProfileCodec.decode(encoded))
        assertFalse(encoded.contains("password"));assertFalse(encoded.contains("ssid"));assertFalse(encoded.contains("phone"))
    }
    @Test fun rejectsForeignFutureDuplicateAndMalformedProfiles() {
        val good=ConnectionProfileCodec.encode(ConnectionProfile("Kuchnia","1234567890123456"))
        for(bad in listOf("https://example.com",good.replace("/v1","/v2"),good+"&client=1234567890123456",good+"&password=test",good.replace("tcp=7779","tcp=0"),good.replace("oven=","oven=999.1.1.1"),"x".repeat(2049))) {
            assertTrue("Should reject profile format",runCatching { ConnectionProfileCodec.decode(bad) }.isFailure)
        }
    }
}
