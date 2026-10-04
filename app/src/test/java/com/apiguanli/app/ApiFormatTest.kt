package com.apiguanli.app

import com.apiguanli.app.data.ApiFormat
import com.apiguanli.app.data.Repo
import com.apiguanli.app.data.Site
import com.apiguanli.app.data.SiteKind
import com.apiguanli.app.data.hasCustomApiPath
import com.apiguanli.app.data.inputUrl
import com.apiguanli.app.data.modelsPath
import com.apiguanli.app.data.protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiFormatTest {
    private fun site(input: String, format: String) = Site(
        id = "t", baseUrl = Repo.normalizeUrl(input), name = "t", kind = SiteKind.KEY,
        apiPath = Repo.apiPathOf(input, format), apiFormat = format,
    )

    @Test
    fun openAi() {
        assertEquals("https://x.com/v1/chat/completions", Repo.previewUrl("x.com", ApiFormat.OPENAI))
        assertEquals("https://x.com/v1/chat/completions", Repo.previewUrl("https://x.com/v1/", ApiFormat.OPENAI))
        assertEquals("https://x.com/v1/chat/completions", Repo.previewUrl("https://x.com/v1/chat/completions", ApiFormat.OPENAI))
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/coding/v3/chat/completions",
            Repo.previewUrl("https://ark.cn-beijing.volces.com/api/coding/v3", ApiFormat.OPENAI),
        )
        val s = site("https://x.com", ApiFormat.OPENAI)
        assertFalse(s.hasCustomApiPath)
        assertEquals("/v1/models", s.modelsPath)
        assertEquals("openai", s.protocol)
    }

    @Test
    fun anthropic() {
        assertEquals("https://x.com/v1/messages", Repo.previewUrl("https://x.com", ApiFormat.ANTHROPIC))
        assertEquals("https://x.com/v1/messages", Repo.previewUrl("https://x.com/v1/messages", ApiFormat.ANTHROPIC))
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            Repo.previewUrl("https://api.deepseek.com/anthropic", ApiFormat.ANTHROPIC),
        )
        val root = site("https://x.com", ApiFormat.ANTHROPIC)
        assertFalse(root.hasCustomApiPath)
        assertEquals("anthropic", root.protocol)
        assertEquals("/v1/models", root.modelsPath)
        val ds = site("https://api.deepseek.com/anthropic", ApiFormat.ANTHROPIC)
        assertTrue(ds.hasCustomApiPath)
        assertEquals("https://api.deepseek.com/anthropic", ds.inputUrl)
    }

    @Test
    fun raw() {
        assertEquals("https://x.com/api/chat", Repo.previewUrl("https://x.com/api/chat", ApiFormat.RAW))
        assertNull(Repo.previewUrl("https://x.com", ApiFormat.RAW))
        val custom = site("https://x.com/api/chat?v=2", ApiFormat.RAW)
        assertEquals("/api/chat?v=2", custom.apiPath)
        assertEquals("openai", custom.protocol)
        assertNull(custom.modelsPath)
        assertTrue(custom.hasCustomApiPath)
        val msg = site("https://x.com/claude/v1/messages", ApiFormat.RAW)
        assertEquals("anthropic", msg.protocol)
        assertEquals("/claude/v1/models", msg.modelsPath)
        // The standard OpenAI endpoint at the root is still a NewAPI-style site.
        assertFalse(site("https://x.com/v1/chat/completions", ApiFormat.RAW).hasCustomApiPath)
    }
}
