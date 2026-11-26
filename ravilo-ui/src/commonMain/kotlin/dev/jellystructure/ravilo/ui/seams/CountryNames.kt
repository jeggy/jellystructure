package dev.jellystructure.ravilo.ui.seams

/** R325 (FR-R325-1) — the table is in `:shared` since DEB-5699 (FR-56), where the Samsung's About block reads it too. */
fun countryName(code: String?): String? = dev.jellystructure.shared.tv.countryName(code)
