package org.example.models

import org.jetbrains.exposed.sql.Table

object Content : Table("content") {
    val id = integer("id").autoIncrement()
    val fileName = varchar("file_name", 255).nullable()
    val fileSize = long("file_size").nullable()
    val contentType = varchar("content_type", 100)
    val status = varchar("status", 255)

    override val primaryKey = PrimaryKey(id, name = "content_map_id")
}

object ContentStorage : Table("content_storage") {
    val is_toxic = bool("is_toxic")
    val content_id = integer("content_id").references(Content.id).index()
    val toxicity = double("toxicity")
    val content = binary("content")

    override val primaryKey = PrimaryKey(content_id, name = "content_id")
}
