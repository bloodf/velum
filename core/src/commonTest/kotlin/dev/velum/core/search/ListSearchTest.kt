package dev.velum.core.search

import kotlin.test.Test
import kotlin.test.assertEquals

class ListSearchTest {
    data class Item(val title: String, val body: String?)

    private val items = listOf(
        Item("Sprint planning", "BLE sync"),
        Item("Product review", null),
        Item("Reunião diária", "standup notes"),
    )

    @Test
    fun emptyQueryReturnsAll() {
        val result = ListSearch.filter(items, "  ") { listOf(it.title, it.body) }
        assertEquals(items, result)
    }

    @Test
    fun filtersByTitleCaseInsensitive() {
        val result = ListSearch.filter(items, "sprint") { listOf(it.title, it.body) }
        assertEquals(listOf(items[0]), result)
    }

    @Test
    fun filtersByBody() {
        val result = ListSearch.filter(items, "standup") { listOf(it.title, it.body) }
        assertEquals(listOf(items[2]), result)
    }

    @Test
    fun noMatchYieldsEmpty() {
        val result = ListSearch.filter(items, "xyz") { listOf(it.title, it.body) }
        assertEquals(emptyList(), result)
    }
}
