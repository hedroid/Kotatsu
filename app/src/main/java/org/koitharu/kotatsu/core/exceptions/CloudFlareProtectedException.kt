package org.koitharu.kotatsu.core.exceptions

import androidx.annotation.StringRes
import okio.IOException
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.ResolvableException

class CloudFlareProtectedException(
	val url: String
) : IOException("Protected by CloudFlare"), ResolvableException {

	@StringRes
	override val resolveTextId: Int = R.string.captcha_solve
}