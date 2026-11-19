package com.monetizationos.sdk.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaywallPageTest {
    @Test
    fun theDocumentIsAboutBlankOrADataUrlWithAnyFragment() {
        assertTrue(isDocumentUrl("about:blank"))
        assertTrue(isDocumentUrl("about:blank#plans"))
        assertTrue(isDocumentUrl("data:text/html;charset=utf-8,%3Cp%3E"))
        assertTrue(isDocumentUrl("DATA:text/html,x"))
    }

    @Test
    fun nothingElseIsTheDocument() {
        assertFalse(isDocumentUrl(null))
        assertFalse(isDocumentUrl("https://evil.example/pay"))
        assertFalse(isDocumentUrl("http://evil.example/about:blank"))
        assertFalse(isDocumentUrl("mos://redeem?token=t"))
        assertFalse(isDocumentUrl("javascript:alert(1)"))
        assertFalse(isDocumentUrl("about:blank-not"))
        assertFalse(isDocumentUrl("https://evil.example/#about:blank"))
    }

    @Test
    fun aPriceFillStartsOnceAndOnlyOnceTheDocumentHasLoaded() {
        val page = PageState()
        assertFalse(page.startFill(), "no fill before the page has loaded")
        assertTrue(page.markLoaded())
        assertFalse(page.markLoaded(), "only the first finish counts")
        assertTrue(page.startFill())
        assertFalse(page.startFill(), "a running fill is not started twice")
        page.fillDone()
        assertFalse(page.startFill(), "a finished fill is not repeated")
    }

    @Test
    fun aNewPageStartsWithNoFill() {
        val page = PageState()
        page.markLoaded()
        page.startFill()
        page.fillDone()
        assertEquals(false, PageState().loaded)
    }
}
