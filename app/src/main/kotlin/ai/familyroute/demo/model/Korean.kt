package ai.familyroute.demo.model

private fun hasBatchim(word: String): Boolean {
    val last = word.lastOrNull { it in '가'..'힣' } ?: return false
    return (last - '가') % 28 != 0
}

/** "휴게소를", "식당을" */
fun String.eulReul(): String = this + if (hasBatchim(this)) "을" else "를"

/** "카페는", "식당은" */
fun String.eunNeun(): String = this + if (hasBatchim(this)) "은" else "는"
