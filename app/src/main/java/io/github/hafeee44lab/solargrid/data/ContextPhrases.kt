package io.github.hafeee44lab.solargrid.data

import android.content.Context
import io.github.hafeee44lab.solargrid.domain.Phrases

/** The domain layer's phrases in this context's language, for services, widgets and the tile. */
fun Context.phrases(): Phrases = Phrases.of(resources.configuration.locales[0])
