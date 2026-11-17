package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.snippets.Snippet

internal const val SnippetPageSize = 50
internal fun snippetPageCount(count: Int): Int = ((count.coerceAtLeast(1) - 1) / SnippetPageSize) + 1
internal fun snippetPage(snippets: List<Snippet>, page: Int): List<Snippet> =
    snippets.drop(page.coerceIn(0, snippetPageCount(snippets.size) - 1) * SnippetPageSize).take(SnippetPageSize)
internal fun reorderedSnippetPage(all: List<Snippet>, ordered: List<Snippet>): List<Snippet> {
    val byId = all.associateBy { it.id }
    return regrouped(all, ordered).map { byId.getValue(it) }
}
internal fun boundedSkeletonRows(count: Int): Int = count.coerceIn(0, 48)
