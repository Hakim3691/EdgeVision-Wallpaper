package com.androidclear.wallpaper.renames

enum class Mapping(
  val punctuation: String,
  val token: String
) {
  SPACE(" ", "SPACE"),
  COMMA(",", "COMMA"),
  PERIOD(".", "PERIOD"),
  EXCLAMATION("!", "EXCLAMATION"),
  QUESTION("?", "QUESTION"),
  DASH("-", "DASH"),
  UNDERSCORE("_", "UNDERSCORE"),
  NEWLINE("\n", "NEWLINE")
}
