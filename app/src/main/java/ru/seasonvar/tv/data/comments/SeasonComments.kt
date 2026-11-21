package ru.seasonvar.tv.data.comments

data class SeasonComment(
    val id: String,
    val reply: Boolean,
    val author: String?,
    val date: String?,
    val parts: List<CommentPart>,
)

sealed interface CommentPart {
    data class Text(val value: String) : CommentPart
    data class Spoiler(val parts: List<CommentPart>) : CommentPart
}

data class CommentsPage(val entries: List<SeasonComment>, val next: Int? = null)
