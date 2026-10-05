// SPDX-License-Identifier: GPL-3.0-or-later

package net.drehtuer.podsilo.core.naming

import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateTokenTest {
    @Test
    fun `a template that is only a variable`() {
        assertEquals(
            listOf(TemplateToken.Variable("podcast", null)),
            tokenizeTemplate("{podcast}"),
        )
    }

    @Test
    fun `the default file template - date underscore title`() {
        assertEquals(
            listOf(
                TemplateToken.Variable("date", null),
                TemplateToken.Literal("_"),
                TemplateToken.Variable("title", null),
            ),
            tokenizeTemplate("{date}_{title}"),
        )
    }

    @Test
    fun `date with an explicit pattern`() {
        assertEquals(
            listOf(TemplateToken.Variable("date", "yyyy-MM-dd")),
            tokenizeTemplate("{date:yyyy-MM-dd}"),
        )
    }

    @Test
    fun `leading and trailing literal text is preserved`() {
        assertEquals(
            listOf(
                TemplateToken.Literal("prefix-"),
                TemplateToken.Variable("title", null),
                TemplateToken.Literal("-suffix"),
            ),
            tokenizeTemplate("prefix-{title}-suffix"),
        )
    }

    @Test
    fun `a plain literal template with no variables`() {
        assertEquals(
            listOf(TemplateToken.Literal("Fixed Name")),
            tokenizeTemplate("Fixed Name"),
        )
    }

    @Test
    fun `an unknown variable name is kept as literal text, braces and all`() {
        assertEquals(
            listOf(TemplateToken.Literal("{nonsense}")),
            tokenizeTemplate("{nonsense}"),
        )
    }

    @Test
    fun `ext is not resolved as a variable -- it is always appended by the caller`() {
        // Adjacent literal runs aren't merged -- "." and "{ext}" surface as two Literal tokens --
        // but DefaultNamingTemplateEngine just concatenates token text, so this is harmless.
        assertEquals(
            listOf(
                TemplateToken.Variable("title", null),
                TemplateToken.Literal("."),
                TemplateToken.Literal("{ext}"),
            ),
            tokenizeTemplate("{title}.{ext}"),
        )
    }

    @Test
    fun `empty template yields no tokens`() {
        assertEquals(emptyList<TemplateToken>(), tokenizeTemplate(""))
    }

    @Test
    fun `an empty date pattern is kept as an empty pattern, not dropped`() {
        assertEquals(listOf(TemplateToken.Variable("date", "")), tokenizeTemplate("{date:}"))
    }

    @Test
    fun `an unclosed brace is literal text`() {
        assertEquals(listOf(TemplateToken.Literal("{title")), tokenizeTemplate("{title"))
    }
}
